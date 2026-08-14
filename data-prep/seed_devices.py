import os
import random
from collections import Counter
from dataclasses import dataclass
from decimal import Decimal

import psycopg2
from psycopg2.extras import execute_values


BUILDING_LIMIT = 45
LEVEL_HEIGHT = Decimal("3.2")
RANDOM_SEED = 42
# ST_GeneratePoints 的固定种子，保证重复执行点位不变
GEOMETRY_SEED = 1337
# 面积达到该阈值（平方度，约 2500 平方米）的建筑额外布一台电气设备，使总数超过 200
EXTRA_DEVICE_AREA_THRESHOLD = 2.5e-7
BASE_DEVICE_TYPES = ("SMOKE", "WATER", "TEMP_HUMI", "ELECTRIC", "CAMERA")
EXTRA_DEVICE_TYPE = "ELECTRIC"
DEVICE_TYPE_NAMES = {
    "SMOKE": "烟感",
    "WATER": "水浸",
    "TEMP_HUMI": "温湿度",
    "ELECTRIC": "电气",
    "CAMERA": "摄像头",
}
# 状态分布：90% 在线，6% 离线，4% 故障
STATUS_WEIGHTS = (("ONLINE", 90), ("OFFLINE", 6), ("FAULT", 4))


@dataclass(frozen=True)
class DeviceRow:
    device_code: str
    device_name: str
    device_type: str
    building_id: int
    floor: int
    altitude: Decimal
    status: str
    point_index: int


SELECT_BUILDING_SQL = """
SELECT
    id,
    name,
    levels,
    height,
    ST_Area(footprint) AS footprint_area
FROM t_building
ORDER BY (name IS NOT NULL) DESC, ST_Area(footprint) DESC, id
LIMIT %s
"""

# 每栋建筑一次性生成 n 个随机点，按 point_index 取用；几何运算全部留在 PostGIS 侧
UPSERT_SQL = """
WITH input_data (
    device_code, device_name, device_type, building_id, floor, altitude, status,
    point_index, point_total, point_seed
) AS (
    VALUES %s
), located AS (
    SELECT
        input_data.*,
        ST_GeometryN(
            ST_GeneratePoints(building.footprint, input_data.point_total, input_data.point_seed),
            input_data.point_index
        ) AS location
    FROM input_data
    JOIN t_building AS building ON building.id = input_data.building_id
), upserted AS (
    INSERT INTO t_device (
        device_code, device_name, device_type, building_id, floor, location, altitude, status, install_time
    )
    SELECT
        device_code,
        device_name,
        device_type,
        building_id,
        floor,
        location,
        altitude,
        status,
        now()
    FROM located
    WHERE location IS NOT NULL
    ON CONFLICT (device_code) DO UPDATE SET
        device_name = EXCLUDED.device_name,
        device_type = EXCLUDED.device_type,
        building_id = EXCLUDED.building_id,
        floor = EXCLUDED.floor,
        location = EXCLUDED.location,
        altitude = EXCLUDED.altitude,
        status = EXCLUDED.status
    RETURNING device_type
)
SELECT device_type FROM upserted
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


def resolve_levels(levels: int | None, height: Decimal | None) -> int:
    if levels is not None and levels > 0:
        return levels
    if height is not None and height > 0:
        return max(1, int(height / LEVEL_HEIGHT))
    return 1


def pick_status(generator: random.Random) -> str:
    statuses = [status for status, _ in STATUS_WEIGHTS]
    weights = [weight for _, weight in STATUS_WEIGHTS]
    return generator.choices(statuses, weights=weights, k=1)[0]


def device_types_for(footprint_area: float) -> tuple[str, ...]:
    if footprint_area is not None and footprint_area >= EXTRA_DEVICE_AREA_THRESHOLD:
        return BASE_DEVICE_TYPES + (EXTRA_DEVICE_TYPE,)
    return BASE_DEVICE_TYPES


def build_rows(buildings: list[tuple], generator: random.Random) -> list[DeviceRow]:
    rows = []
    for building_id, building_name, levels, height, footprint_area in buildings:
        device_types = device_types_for(footprint_area)
        top_floor = resolve_levels(levels, height)
        display_name = building_name or f"建筑{building_id}"
        for offset, device_type in enumerate(device_types, start=1):
            floor = generator.randint(1, top_floor)
            rows.append(DeviceRow(
                device_code=f"DEV-{building_id}-{offset}",
                device_name=f"{display_name}-{DEVICE_TYPE_NAMES[device_type]}{offset}",
                device_type=device_type,
                building_id=building_id,
                floor=floor,
                altitude=Decimal(floor) * LEVEL_HEIGHT,
                status=pick_status(generator),
                point_index=offset,
            ))
    return rows


def row_values(row: DeviceRow, point_total: int) -> tuple:
    return (
        row.device_code,
        row.device_name,
        row.device_type,
        row.building_id,
        row.floor,
        row.altitude,
        row.status,
        row.point_index,
        point_total,
        GEOMETRY_SEED,
    )


def main() -> None:
    generator = random.Random(RANDOM_SEED)
    status_counts = Counter()
    type_counts = Counter()

    with psycopg2.connect(**connection_parameters()) as connection:
        with connection.cursor() as cursor:
            cursor.execute(SELECT_BUILDING_SQL, (BUILDING_LIMIT,))
            buildings = cursor.fetchall()
            if not buildings:
                raise RuntimeError("t_building 无数据，请先执行 load_to_pg.py")

            rows = build_rows(buildings, generator)
            device_codes = [row.device_code for row in rows]
            cursor.execute("SELECT count(*) FROM t_device WHERE device_code = ANY(%s)", (device_codes,))
            update_count = cursor.fetchone()[0]
            print(f"写入前确认：覆盖建筑 {len(buildings)} 栋，将新增最多 {len(rows) - update_count} 条，更新 {update_count} 条")

            # 同一建筑的设备共享一次 ST_GeneratePoints 结果，point_total 取该建筑的设备数
            point_totals = Counter(row.building_id for row in rows)
            returned = execute_values(
                cursor,
                UPSERT_SQL,
                [row_values(row, point_totals[row.building_id]) for row in rows],
                template="(%s::varchar, %s::varchar, %s::varchar, %s::bigint, %s::integer, %s::numeric, %s::varchar, %s::integer, %s::integer, %s::integer)",
                page_size=len(rows),
                fetch=True,
            )
            for result in returned:
                type_counts[result[0]] += 1

    for row in rows:
        status_counts[row.status] += 1

    print(f"计划写入设备数：{len(rows)}")
    print(f"实际入库设备数：{sum(type_counts.values())}")
    for device_type in ("SMOKE", "WATER", "TEMP_HUMI", "ELECTRIC", "CAMERA"):
        print(f"{device_type}：{type_counts[device_type]}")
    for status, _ in STATUS_WEIGHTS:
        print(f"{status}：{status_counts[status]}")


if __name__ == "__main__":
    main()
