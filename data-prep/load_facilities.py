import json
import os
from collections import Counter
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path

import psycopg2
from psycopg2.extras import Json, execute_values


INPUT_PATH = Path(__file__).resolve().parent / "output" / "osm_facilities.json"
BATCH_SIZE = 500
# 路灯与井盖目标数量。OSM 武汉的 highway=street_lamp / man_made=manhole 标注极其稀疏，
# 直接取标注可能只有个位数，故改为沿道路按「总里程 / 目标数量」反推间距插值，数量可控
TARGET_STREET_LAMP = 800
TARGET_MANHOLE = 300
# 反推出的间距不得小于该下限（米），避免道路总里程偏小时点位挤成一串
MIN_LAMP_SPACING_METERS = 30
MIN_MANHOLE_SPACING_METERS = 100
# 路灯灯头高度（米），井盖与地面齐平，充电桩与公交站牌按地面算
STREET_LAMP_ALTITUDE = Decimal("6")
GROUND_ALTITUDE = Decimal("0")
# setseed 的定值，保证重复执行时状态分布与选点不变
RANDOM_SEED = 0.42
# 状态分布：90% 在线，6% 离线，4% 故障，与 seed_devices.py 保持一致
OFFLINE_THRESHOLD = 0.90
FAULT_THRESHOLD = 0.96
FACILITY_TYPE_NAMES = {
    "CHARGING_PILE": "充电桩",
    "STREET_LAMP": "路灯",
    "MANHOLE": "井盖",
    "BUS_STOP": "公交站",
}
# facility_code 前缀，按类型区分，OSM 直取的拼 osm_id，插值的拼「道路 id + 序号」
CODE_PREFIXES = {
    "CHARGING_PILE": "CP",
    "STREET_LAMP": "SL",
    "MANHOLE": "MH",
    "BUS_STOP": "BS",
}
ROAD_TYPES = ("trunk", "primary", "secondary", "tertiary", "residential")


@dataclass(frozen=True)
class RoadRow:
    osm_id: int
    name: str | None
    road_type: str
    coordinates: list[list[float]]


@dataclass(frozen=True)
class FacilityRow:
    facility_code: str
    facility_name: str
    facility_type: str
    osm_id: int
    lon: float
    lat: float
    altitude: Decimal


# 道路入库：坐标数组在 PostGIS 侧拼成 LineString，长度用 geography 算真实米数
UPSERT_ROAD_SQL = """
WITH input_data (osm_id, name, road_type, coordinates) AS (
    VALUES %s
), lines AS (
    SELECT
        osm_id,
        name,
        road_type,
        ST_MakeLine(ARRAY(
            SELECT ST_SetSRID(
                ST_MakePoint((coordinate.value->>0)::double precision, (coordinate.value->>1)::double precision),
                4326
            )
            FROM jsonb_array_elements(coordinates) WITH ORDINALITY AS coordinate(value, position)
            ORDER BY coordinate.position
        )) AS geom
    FROM input_data
), upserted AS (
    INSERT INTO t_road (osm_id, name, road_type, geom, length_m)
    SELECT osm_id, name, road_type, geom, ST_Length(geom::geography)
    FROM lines
    WHERE GeometryType(geom) = 'LINESTRING' AND ST_NPoints(geom) >= 2
    ON CONFLICT (osm_id) DO UPDATE SET
        name = EXCLUDED.name,
        road_type = EXCLUDED.road_type,
        geom = EXCLUDED.geom,
        length_m = EXCLUDED.length_m
    RETURNING road_type
)
SELECT road_type FROM upserted
"""

# OSM 直取的点位入库，状态按单次 random() 取值分档
UPSERT_OSM_FACILITY_SQL = """
WITH input_data (facility_code, facility_name, facility_type, osm_id, lon, lat, altitude) AS (
    VALUES %s
), dice AS (
    SELECT input_data.*, random() AS luck FROM input_data
), upserted AS (
    INSERT INTO t_facility (
        facility_code, facility_name, facility_type, osm_id, road_id,
        location, altitude, status, source, install_time
    )
    SELECT
        facility_code,
        facility_name,
        facility_type,
        osm_id,
        NULL,
        ST_SetSRID(ST_MakePoint(lon, lat), 4326),
        altitude,
        CASE
            WHEN luck < %(offline_threshold)s THEN 'ONLINE'
            WHEN luck < %(fault_threshold)s THEN 'OFFLINE'
            ELSE 'FAULT'
        END,
        'osm',
        now()
    FROM dice
    ON CONFLICT (facility_code) DO UPDATE SET
        facility_name = EXCLUDED.facility_name,
        facility_type = EXCLUDED.facility_type,
        osm_id = EXCLUDED.osm_id,
        location = EXCLUDED.location,
        altitude = EXCLUDED.altitude,
        source = EXCLUDED.source
    RETURNING facility_type
)
SELECT facility_type FROM upserted
"""

# 沿道路插值：间距 = 道路总里程 / 目标数量，再与下限取大值；
# 每条路按 generate_series 均匀取点，几何运算全部留在 PostGIS 侧
INTERPOLATE_FACILITY_SQL = """
WITH total AS (
    SELECT sum(length_m) AS total_m FROM t_road
), step AS (
    SELECT greatest(total.total_m / %(target)s, %(min_spacing)s) AS spacing_m FROM total
), points AS (
    SELECT
        road.id AS road_id,
        series.index AS point_index,
        road.name AS road_name,
        random() AS luck,
        ST_LineInterpolatePoint(
            road.geom,
            least(series.index * step.spacing_m / road.length_m, 1.0)::double precision
        ) AS location
    FROM t_road AS road
    CROSS JOIN step
    CROSS JOIN generate_series(0, floor(road.length_m / step.spacing_m)::integer) AS series(index)
    WHERE road.length_m >= step.spacing_m
), upserted AS (
    INSERT INTO t_facility (
        facility_code, facility_name, facility_type, osm_id, road_id,
        location, altitude, status, source, install_time
    )
    SELECT
        %(code_prefix)s || points.road_id || '-' || points.point_index,
        %(type_name)s || coalesce(' · ' || points.road_name, '') || ' ' || points.point_index || ' 号',
        %(facility_type)s,
        NULL,
        points.road_id,
        points.location,
        %(altitude)s,
        CASE
            WHEN points.luck < %(offline_threshold)s THEN 'ONLINE'
            WHEN points.luck < %(fault_threshold)s THEN 'OFFLINE'
            ELSE 'FAULT'
        END,
        'road_interp',
        now()
    FROM points
    ON CONFLICT (facility_code) DO UPDATE SET
        facility_name = EXCLUDED.facility_name,
        facility_type = EXCLUDED.facility_type,
        road_id = EXCLUDED.road_id,
        location = EXCLUDED.location,
        altitude = EXCLUDED.altitude,
        source = EXCLUDED.source
    RETURNING facility_type
)
SELECT count(*) FROM upserted
"""


def connection_parameters() -> dict:
    password = os.getenv("PGPASSWORD")
    if not password:
        raise RuntimeError("缺少环境变量 PGPASSWORD")
    return {
        "host": os.getenv("PGHOST", "localhost"),
        "port": int(os.getenv("PGPORT", "5434")),
        "dbname": os.getenv("PGDATABASE", "twin"),
        "user": os.getenv("PGUSER", "twin"),
        "password": password,
    }


def build_road_row(element: dict) -> RoadRow | None:
    tags = element.get("tags") if isinstance(element.get("tags"), dict) else {}
    road_type = tags.get("highway")
    if road_type not in ROAD_TYPES:
        return None
    geometry = element.get("geometry")
    if not isinstance(geometry, list) or len(geometry) < 2:
        return None
    coordinates = []
    for point in geometry:
        if not isinstance(point, dict) or "lon" not in point or "lat" not in point:
            return None
        coordinates.append([float(point["lon"]), float(point["lat"])])
    return RoadRow(
        osm_id=int(element["id"]),
        name=tags.get("name"),
        road_type=road_type,
        coordinates=coordinates,
    )


def resolve_point_type(tags: dict) -> str | None:
    if tags.get("amenity") == "charging_station":
        return "CHARGING_PILE"
    if tags.get("highway") == "bus_stop":
        return "BUS_STOP"
    if tags.get("public_transport") == "platform" and tags.get("bus") == "yes":
        return "BUS_STOP"
    return None


def build_facility_row(element: dict) -> FacilityRow | None:
    if "lon" not in element or "lat" not in element:
        return None
    tags = element.get("tags") if isinstance(element.get("tags"), dict) else {}
    facility_type = resolve_point_type(tags)
    if facility_type is None:
        return None
    osm_id = int(element["id"])
    type_name = FACILITY_TYPE_NAMES[facility_type]
    osm_name = tags.get("name")
    return FacilityRow(
        facility_code=f"{CODE_PREFIXES[facility_type]}{osm_id}",
        facility_name=f"{type_name} · {osm_name}" if osm_name else f"{type_name}{osm_id}",
        facility_type=facility_type,
        osm_id=osm_id,
        lon=float(element["lon"]),
        lat=float(element["lat"]),
        altitude=GROUND_ALTITUDE,
    )


def load_roads(cursor, roads: list[RoadRow]) -> Counter:
    counts = Counter()
    for start in range(0, len(roads), BATCH_SIZE):
        batch = roads[start:start + BATCH_SIZE]
        returned = execute_values(
            cursor,
            UPSERT_ROAD_SQL,
            [(road.osm_id, road.name, road.road_type, Json(road.coordinates)) for road in batch],
            template="(%s::bigint, %s::varchar, %s::varchar, %s::jsonb)",
            page_size=BATCH_SIZE,
            fetch=True,
        )
        counts.update(result[0] for result in returned)
    return counts


def load_osm_facilities(cursor, facilities: list[FacilityRow]) -> Counter:
    counts = Counter()
    for start in range(0, len(facilities), BATCH_SIZE):
        batch = facilities[start:start + BATCH_SIZE]
        statement = cursor.mogrify(
            UPSERT_OSM_FACILITY_SQL,
            {"offline_threshold": OFFLINE_THRESHOLD, "fault_threshold": FAULT_THRESHOLD},
        ).decode("utf-8")
        returned = execute_values(
            cursor,
            statement,
            [
                (
                    row.facility_code,
                    row.facility_name,
                    row.facility_type,
                    row.osm_id,
                    row.lon,
                    row.lat,
                    row.altitude,
                )
                for row in batch
            ],
            template="(%s::varchar, %s::varchar, %s::varchar, %s::bigint, "
                     "%s::double precision, %s::double precision, %s::numeric)",
            page_size=BATCH_SIZE,
            fetch=True,
        )
        counts.update(result[0] for result in returned)
    return counts


def interpolate_along_roads(cursor, facility_type: str, target: int,
                            min_spacing: int, altitude: Decimal) -> int:
    cursor.execute(
        INTERPOLATE_FACILITY_SQL,
        {
            "target": target,
            "min_spacing": min_spacing,
            "code_prefix": CODE_PREFIXES[facility_type],
            "type_name": FACILITY_TYPE_NAMES[facility_type],
            "facility_type": facility_type,
            "altitude": altitude,
            "offline_threshold": OFFLINE_THRESHOLD,
            "fault_threshold": FAULT_THRESHOLD,
        },
    )
    return cursor.fetchone()[0]


def main() -> None:
    data = json.loads(INPUT_PATH.read_text(encoding="utf-8"))
    elements = data.get("elements", [])
    roads = [row for element in elements
             if element.get("type") == "way" and (row := build_road_row(element)) is not None]
    osm_facilities = [row for element in elements
                      if element.get("type") == "node" and (row := build_facility_row(element)) is not None]

    with psycopg2.connect(**connection_parameters()) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT count(*) FROM t_road")
            print(f"写入前 t_road 现有 {cursor.fetchone()[0]} 条，本轮提交 {len(roads)} 条")
            cursor.execute("SELECT count(*) FROM t_facility")
            print(f"写入前 t_facility 现有 {cursor.fetchone()[0]} 条")

            # setseed 让后续 random() 序列固定，重复执行时状态分布不变
            cursor.execute("SELECT setseed(%s)", (RANDOM_SEED,))
            road_counts = load_roads(cursor, roads)
            cursor.execute("SELECT sum(length_m) FROM t_road")
            total_length = cursor.fetchone()[0] or 0

            facility_counts = load_osm_facilities(cursor, osm_facilities)
            lamp_count = interpolate_along_roads(
                cursor, "STREET_LAMP", TARGET_STREET_LAMP,
                MIN_LAMP_SPACING_METERS, STREET_LAMP_ALTITUDE,
            )
            manhole_count = interpolate_along_roads(
                cursor, "MANHOLE", TARGET_MANHOLE,
                MIN_MANHOLE_SPACING_METERS, GROUND_ALTITUDE,
            )

    print(f"道路入库：{sum(road_counts.values())} 条，总里程 {total_length / 1000:.1f} 公里")
    for road_type in ROAD_TYPES:
        print(f"  {road_type}：{road_counts[road_type]}")
    print(f"OSM 直取点位：{sum(facility_counts.values())} 个")
    for facility_type in ("CHARGING_PILE", "BUS_STOP"):
        print(f"  {FACILITY_TYPE_NAMES[facility_type]}：{facility_counts[facility_type]}")
    print(f"沿路插值：路灯 {lamp_count} 个，井盖 {manhole_count} 个")


if __name__ == "__main__":
    main()
