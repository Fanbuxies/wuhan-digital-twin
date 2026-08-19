import os
import random
from collections import Counter
from dataclasses import dataclass
from decimal import Decimal

import psycopg2
from psycopg2.extras import execute_values


TARGET_DEVICE_TOTAL = 2000
# 实际入库数不得超过 TARGET_DEVICE_TOTAL：抽样按建筑配额走，最后一栋可能跨过上限，
# 因此在 build_rows 之后按设备数截断，保证 t_device 行数落在目标值以内
DEVICE_HARD_CAP = TARGET_DEVICE_TOTAL
# 分层抽样网格边长（度），约 1.1 公里，保证江滩、昙华林、东南片区都能分到配额
GRID_SIZE_DEGREES = 0.01
# 每个网格至少抽 1 栋，最多抽 6 栋，避免建筑密集区把配额全吃掉
MIN_BUILDINGS_PER_CELL = 1
MAX_BUILDINGS_PER_CELL = 6
# 每栋布 5 台基础设备，大体量建筑额外加 1 台。
# 注意：该常量已不再参与配额计算——固定 5.5 与实际抽样均值（接近 6.0）偏差导致超出目标 22%，
# 现由 sample_buildings 按候选建筑实际设备数动态求均值。保留仅作历史参考。
ESTIMATED_DEVICES_PER_BUILDING = 5.5
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


# 按经纬度网格分层：先给每栋建筑打上所属网格与格内面积排名，配额在 Python 侧按格内建筑数分配
SELECT_BUILDING_SQL = """
WITH celled AS (
    SELECT
        id,
        name,
        levels,
        height,
        ST_Area(footprint) AS footprint_area,
        floor(ST_X(center) / %(grid_size)s)::integer AS cell_x,
        floor(ST_Y(center) / %(grid_size)s)::integer AS cell_y
    FROM t_building
    WHERE center IS NOT NULL
)
SELECT
    id,
    name,
    levels,
    height,
    footprint_area,
    cell_x,
    cell_y,
    row_number() OVER (PARTITION BY cell_x, cell_y ORDER BY footprint_area DESC, id) AS rank_in_cell,
    count(*) OVER (PARTITION BY cell_x, cell_y) AS cell_building_count
FROM celled
ORDER BY cell_x, cell_y, rank_in_cell
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


def allocate_cell_quotas(
    cell_counts: dict[tuple[int, int], int],
    building_budget: int,
) -> dict[tuple[int, int], int]:
    """按格内建筑数比例分配建筑配额，再按小数余数从大到小补齐到预算"""
    total_buildings = sum(cell_counts.values())
    quotas = {}
    remainders = []
    for cell, count in cell_counts.items():
        exact = count * building_budget / total_buildings
        quotas[cell] = min(count, MAX_BUILDINGS_PER_CELL, max(MIN_BUILDINGS_PER_CELL, int(exact)))
        remainders.append((exact - int(exact), cell))
    remainders.sort(reverse=True)

    progressed = True
    while sum(quotas.values()) < building_budget and progressed:
        progressed = False
        for _, cell in remainders:
            if sum(quotas.values()) >= building_budget:
                break
            if quotas[cell] < min(cell_counts[cell], MAX_BUILDINGS_PER_CELL):
                quotas[cell] += 1
                progressed = True
    return quotas


def sample_buildings(candidates: list[tuple]) -> list[tuple]:
    """candidates 每行为 (id, name, levels, height, area, cell_x, cell_y, rank_in_cell, cell_count)

    抽样按 footprint_area DESC 取格内最大的若干栋，这些恰好是会越过
    EXTRA_DEVICE_AREA_THRESHOLD 拿到第 6 台设备的建筑，因此实际均值接近 6.0 而非
    ESTIMATED_DEVICES_PER_BUILDING 假定的 5.5；再叠加 MIN_BUILDINGS_PER_CELL 对稀疏格
    的强制补齐，旧逻辑会超出目标约 22%。这里改为按候选建筑的真实设备数反推预算。
    """
    cell_counts = {(row[5], row[6]): row[8] for row in candidates}
    # 用候选集里实际会落多少台设备来估均值，避免固定系数在扩面后失真
    device_counts = [len(device_types_for(row[4])) for row in candidates]
    average_devices = sum(device_counts) / len(device_counts)
    building_budget = max(1, int(TARGET_DEVICE_TOTAL / average_devices))
    quotas = allocate_cell_quotas(cell_counts, building_budget)
    return [row[:5] for row in candidates if row[7] <= quotas[(row[5], row[6])]]


def truncate_to_cap(rows: list[DeviceRow], cell_of: dict[int, tuple[int, int]]) -> list[DeviceRow]:
    """按建筑整体截断到 DEVICE_HARD_CAP，避免同一建筑只入库一半设备。

    扩面后网格数（约 2400）远多于预算能覆盖的建筑数，MIN_BUILDINGS_PER_CELL 的下限
    会让候选量大幅超出上限。若按 cell_x, cell_y 顺序直接截断，只会填满最西侧一列网格，
    设备全挤在一条竖带里。因此改为按网格轮转取用，保证七个区都分到设备。
    """
    if len(rows) <= DEVICE_HARD_CAP:
        return rows

    groups: dict[int, list[DeviceRow]] = {}
    for row in rows:
        groups.setdefault(row.building_id, []).append(row)

    # 先把建筑按所属网格归拢，再一轮一轮地从每个网格取一栋
    by_cell: dict[tuple[int, int], list[int]] = {}
    for building_id in groups:
        by_cell.setdefault(cell_of[building_id], []).append(building_id)

    ordered_cells = sorted(by_cell)
    generator = random.Random(RANDOM_SEED)
    generator.shuffle(ordered_cells)

    kept: list[DeviceRow] = []
    depth = 0
    max_depth = max(len(ids) for ids in by_cell.values())
    while depth < max_depth and len(kept) < DEVICE_HARD_CAP:
        for cell in ordered_cells:
            ids = by_cell[cell]
            if depth >= len(ids):
                continue
            group = groups[ids[depth]]
            if len(kept) + len(group) > DEVICE_HARD_CAP:
                continue
            kept.extend(group)
            if len(kept) >= DEVICE_HARD_CAP:
                break
        depth += 1
    return kept


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
            cursor.execute(SELECT_BUILDING_SQL, {"grid_size": GRID_SIZE_DEGREES})
            candidates = cursor.fetchall()
            if not candidates:
                raise RuntimeError("t_building 无数据，请先执行 load_to_pg.py")

            buildings = sample_buildings(candidates)
            cell_total = len({(row[5], row[6]) for row in candidates})
            print(f"候选建筑 {len(candidates)} 栋，覆盖网格 {cell_total} 个，抽中 {len(buildings)} 栋")

            cell_of = {row[0]: (row[5], row[6]) for row in candidates}
            rows = truncate_to_cap(build_rows(buildings, generator), cell_of)
            covered_buildings = len({row.building_id for row in rows})
            print(f"计划设备数 {len(rows)} 台，覆盖建筑 {covered_buildings} 栋，上限 {DEVICE_HARD_CAP}")
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
