import json
import os
import re
from collections import Counter
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path

import psycopg2
from psycopg2.extras import Json, execute_values


INPUT_PATH = Path(__file__).resolve().parent / "output" / "osm_raw.json"
BATCH_SIZE = 500
MIN_HEIGHT = Decimal("3")
MAX_HEIGHT = Decimal("300")
LEVEL_HEIGHT = Decimal("3.2")
DEFAULT_HEIGHTS = {
    "residential": Decimal("30"),
    "apartments": Decimal("30"),
    "office": Decimal("40"),
    "commercial": Decimal("24"),
    "retail": Decimal("18"),
    "industrial": Decimal("12"),
    "school": Decimal("15"),
}
NUMBER_PATTERN = re.compile(r"[-+]?\d+(?:\.\d+)?")
# OSM 中 way 与 relation 的 id 空间独立，同号会撞 t_building.osm_id 唯一键，relation 一律存负值
RELATION_ID_SIGN = -1
# 拼接环时判断两点是否为同一节点的精度，1e-7 度约合 1 厘米
COORDINATE_PRECISION = 7


@dataclass(frozen=True)
class BuildingRow:
    osm_id: int
    name: str | None
    building_type: str | None
    levels: int | None
    height: Decimal
    height_source: str
    coordinates: list[list[float]]


def parse_number(value: object) -> Decimal | None:
    if value is None:
        return None
    match = NUMBER_PATTERN.search(str(value).strip())
    if match is None:
        return None
    try:
        return Decimal(match.group())
    except InvalidOperation:
        return None


def parse_levels(value: object) -> int | None:
    number = parse_number(value)
    if number is None or number < 0 or number != number.to_integral_value():
        return None
    return int(number)


def default_height(building_type: str | None) -> Decimal:
    return DEFAULT_HEIGHTS.get(building_type or "", Decimal("15"))


def derive_height(tags: dict) -> tuple[Decimal, str, int | None]:
    building_type = tags.get("building")
    levels = parse_levels(tags.get("building:levels"))
    raw_height = tags.get("height")
    parsed_height = parse_number(raw_height)

    if parsed_height is not None:
        if MIN_HEIGHT <= parsed_height <= MAX_HEIGHT:
            return parsed_height, "osm_height", levels
        return default_height(building_type), "default_by_type", levels

    if raw_height is None and levels is not None:
        level_based_height = Decimal(levels) * LEVEL_HEIGHT
        if MIN_HEIGHT <= level_based_height <= MAX_HEIGHT:
            return level_based_height, "osm_levels", levels
        return default_height(building_type), "default_by_type", levels

    if raw_height is not None and levels is not None:
        level_based_height = Decimal(levels) * LEVEL_HEIGHT
        if MIN_HEIGHT <= level_based_height <= MAX_HEIGHT:
            return level_based_height, "osm_levels", levels

    return default_height(building_type), "default_by_type", levels


def normalize_coordinates(geometry: object) -> list[list[float]] | None:
    if not isinstance(geometry, list) or len(geometry) < 4:
        return None
    coordinates = []
    for point in geometry:
        if not isinstance(point, dict) or "lon" not in point or "lat" not in point:
            return None
        coordinates.append([float(point["lon"]), float(point["lat"])])
    if coordinates[0] != coordinates[-1]:
        coordinates.append(coordinates[0])
    return coordinates if len(coordinates) >= 4 else None


def point_key(point: list[float]) -> tuple[float, float]:
    return (round(point[0], COORDINATE_PRECISION), round(point[1], COORDINATE_PRECISION))


def member_points(member: object) -> list[list[float]] | None:
    if not isinstance(member, dict) or member.get("type") != "way":
        return None
    # multipolygon 用 outer 标外环，type=building 关系用 outline 标建筑轮廓，少数数据 role 留空
    # inner 内院与 part 建筑部件本轮都不参与，前者不挖洞，后者被 outline 包住
    if member.get("role") not in ("outer", "outline", "", None):
        return None
    geometry = member.get("geometry")
    if not isinstance(geometry, list) or len(geometry) < 2:
        return None
    points = []
    for point in geometry:
        if not isinstance(point, dict) or "lon" not in point or "lat" not in point:
            return None
        points.append([float(point["lon"]), float(point["lat"])])
    return points if len(points) >= 2 else None


def stitch_rings(segments: list[list[list[float]]]) -> list[list[list[float]]]:
    """外环可能被切成多段 way，按首尾相接拼成闭合环，拼不上的整段丢弃"""
    pending = list(segments)
    rings = []
    while pending:
        ring = pending.pop(0)
        extended = True
        while point_key(ring[0]) != point_key(ring[-1]) and extended:
            extended = False
            for index, segment in enumerate(pending):
                if point_key(segment[0]) == point_key(ring[-1]):
                    ring = ring + segment[1:]
                elif point_key(segment[-1]) == point_key(ring[-1]):
                    ring = ring + list(reversed(segment))[1:]
                elif point_key(segment[-1]) == point_key(ring[0]):
                    ring = segment[:-1] + ring
                elif point_key(segment[0]) == point_key(ring[0]):
                    ring = list(reversed(segment))[:-1] + ring
                else:
                    continue
                pending.pop(index)
                extended = True
                break
        if point_key(ring[0]) == point_key(ring[-1]) and len(ring) >= 4:
            rings.append(ring)
    return rings


def ring_area(ring: list[list[float]]) -> float:
    """鞋带公式算环面积（平方度），仅用于在多个外环中挑面积最大的那个作为轮廓"""
    total = 0.0
    for index in range(len(ring) - 1):
        current = ring[index]
        following = ring[index + 1]
        total += current[0] * following[1] - following[0] * current[1]
    return abs(total) / 2.0


def relation_coordinates(element: dict) -> list[list[float]] | None:
    members = element.get("members")
    if not isinstance(members, list):
        return None
    segments = [points for member in members if (points := member_points(member)) is not None]
    rings = stitch_rings(segments)
    if not rings:
        return None
    return max(rings, key=ring_area)


def build_row(element: object) -> BuildingRow | None:
    if not isinstance(element, dict):
        return None
    element_type = element.get("type")
    if element_type == "way":
        osm_id = int(element["id"])
        coordinates = normalize_coordinates(element.get("geometry"))
    elif element_type == "relation":
        osm_id = RELATION_ID_SIGN * int(element["id"])
        coordinates = relation_coordinates(element)
    else:
        return None
    if coordinates is None:
        return None
    tags = element.get("tags") if isinstance(element.get("tags"), dict) else {}
    height, height_source, levels = derive_height(tags)
    return BuildingRow(
        osm_id=osm_id,
        name=tags.get("name"),
        building_type=tags.get("building"),
        levels=levels,
        height=height,
        height_source=height_source,
        coordinates=coordinates,
    )


UPSERT_SQL = """
WITH input_data (osm_id, name, building_type, levels, height, height_source, coordinates) AS (
    VALUES %s
), polygons AS (
    SELECT
        osm_id,
        name,
        building_type,
        levels,
        height,
        height_source,
        ST_MakePolygon(ST_MakeLine(ARRAY(
            SELECT ST_SetSRID(
                ST_MakePoint((coordinate.value->>0)::double precision, (coordinate.value->>1)::double precision),
                4326
            )
            FROM jsonb_array_elements(coordinates) WITH ORDINALITY AS coordinate(value, position)
            ORDER BY coordinate.position
        ))) AS footprint
    FROM input_data
), repaired AS (
    SELECT
        osm_id,
        name,
        building_type,
        levels,
        height,
        height_source,
        CASE WHEN ST_IsValid(footprint) THEN footprint ELSE ST_MakeValid(footprint) END AS footprint
    FROM polygons
), upserted AS (
    INSERT INTO t_building (
        osm_id, name, building_type, levels, height, height_source, base_altitude, footprint, center
    )
    SELECT
        osm_id,
        name,
        building_type,
        levels,
        height,
        height_source,
        0,
        footprint,
        ST_Centroid(footprint)
    FROM repaired
    WHERE GeometryType(footprint) = 'POLYGON' AND ST_IsValid(footprint)
      -- 行政区裁剪：只保留与中心城区七区边界相交的建筑，
      -- 把矩形 bbox 带进来的蔡甸/江夏/东西湖等新城区边角剔掉。
      -- 用 EXISTS + GIST 索引逐栋判定，不预先 ST_Union（并集顶点数巨大，每批重算更慢）
      AND EXISTS (
          SELECT 1 FROM t_district AS district
          WHERE ST_Intersects(repaired.footprint, district.boundary)
      )
    ON CONFLICT (osm_id) DO UPDATE SET
        name = EXCLUDED.name,
        building_type = EXCLUDED.building_type,
        levels = EXCLUDED.levels,
        height = EXCLUDED.height,
        height_source = EXCLUDED.height_source,
        base_altitude = EXCLUDED.base_altitude,
        footprint = EXCLUDED.footprint,
        center = EXCLUDED.center
    RETURNING height_source
)
SELECT height_source FROM upserted
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


def row_values(row: BuildingRow) -> tuple:
    return (
        row.osm_id,
        row.name,
        row.building_type,
        row.levels,
        row.height,
        row.height_source,
        Json(row.coordinates),
    )


def main() -> None:
    data = json.loads(INPUT_PATH.read_text(encoding="utf-8"))
    elements = data.get("elements", [])
    rows = [row for element in elements if (row := build_row(element)) is not None]
    source_counts = Counter()

    with psycopg2.connect(**connection_parameters()) as connection:
        with connection.cursor() as cursor:
            # 裁剪依赖 t_district，空表会让 EXISTS 过滤掉全部建筑，先失败快过静默入库 0 条
            cursor.execute("SELECT count(*) FROM t_district")
            district_count = cursor.fetchone()[0]
            if district_count == 0:
                raise RuntimeError("t_district 为空，先执行 load_districts.py 落七区边界再入库建筑")
            print(f"行政区裁剪基准：t_district {district_count} 个区")

            osm_ids = [row.osm_id for row in rows]
            cursor.execute("SELECT count(*) FROM t_building WHERE osm_id = ANY(%s)", (osm_ids,))
            update_count = cursor.fetchone()[0]
            print(f"写入前确认：将新增最多 {len(rows) - update_count} 条，更新 {update_count} 条")

            for start in range(0, len(rows), BATCH_SIZE):
                batch = rows[start:start + BATCH_SIZE]
                returned = execute_values(
                    cursor,
                    UPSERT_SQL,
                    [row_values(row) for row in batch],
                    template="(%s::bigint, %s::varchar, %s::varchar, %s::integer, %s::numeric, %s::varchar, %s::jsonb)",
                    page_size=BATCH_SIZE,
                    fetch=True,
                )
                source_counts.update(result[0] for result in returned)

    loaded_count = sum(source_counts.values())
    skipped_count = len(elements) - loaded_count
    relation_count = sum(1 for row in rows if row.osm_id < 0)
    print(f"总要素数：{len(elements)}")
    print(f"解析出轮廓的 relation 数：{relation_count}")
    print(f"成功入库数：{loaded_count}")
    print(f"跳过数：{skipped_count}（含解析不出轮廓、几何无效，以及被行政区裁剪剔除的范围外建筑）")
    for source in ("osm_height", "osm_levels", "default_by_type"):
        print(f"{source}：{source_counts[source]}")


if __name__ == "__main__":
    main()
