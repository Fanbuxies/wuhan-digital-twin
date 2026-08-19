import json
import os
from pathlib import Path

import psycopg2
from psycopg2.extras import Json, execute_values


INPUT_PATH = Path(__file__).resolve().parent / "output" / "district_boundaries.json"

# 七区中文名以 OSM relation 的 name 标签为准，这里只做兜底（个别 relation 可能缺 name:zh）
FALLBACK_NAMES = {
    3077255: "江岸区",
    3077256: "江汉区",
    3077257: "硚口区",
    3076295: "汉阳区",
    3076297: "武昌区",
    3079613: "青山区",
    3080399: "洪山区",
}

# 行政边界 relation 的成员是若干段 way，需先拼合再成面。
# 拼环逻辑不在 Python 侧做：ST_Node 打断自交后由 ST_BuildArea 成面，几何运算全留在 PostGIS
UPSERT_SQL = """
WITH input_data (osm_id, name, lines) AS (
    VALUES %s
), segments AS (
    SELECT
        input_data.osm_id,
        input_data.name,
        ST_SetSRID(ST_MakeLine(ARRAY(
            SELECT ST_MakePoint((point.value->>0)::double precision, (point.value->>1)::double precision)
            FROM jsonb_array_elements(line.value) WITH ORDINALITY AS point(value, position)
            ORDER BY point.position
        )), 4326) AS line
    FROM input_data, jsonb_array_elements(input_data.lines) AS line(value)
), built AS (
    SELECT
        osm_id,
        name,
        ST_Multi(ST_CollectionExtract(
            ST_MakeValid(ST_BuildArea(ST_Node(ST_Collect(line)))), 3
        )) AS boundary
    FROM segments
    WHERE ST_NPoints(line) >= 2
    GROUP BY osm_id, name
), upserted AS (
    INSERT INTO t_district (osm_id, name, boundary)
    SELECT osm_id, name, boundary
    FROM built
    WHERE boundary IS NOT NULL AND NOT ST_IsEmpty(boundary)
    ON CONFLICT (osm_id) DO UPDATE SET
        name = EXCLUDED.name,
        boundary = EXCLUDED.boundary
    RETURNING osm_id, name, boundary
)
SELECT
    osm_id,
    name,
    ST_NPoints(boundary) AS npoints,
    round((ST_Area(boundary::geography) / 1000000)::numeric, 1) AS area_km2
FROM upserted
ORDER BY osm_id
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


def member_lines(element: dict) -> list[list[list[float]]]:
    """取 relation 成员 way 的坐标串。inner 角色（飞地/内嵌区）一并参与成面，由 ST_BuildArea 自行判定内外环"""
    lines = []
    for member in element.get("members", []):
        if not isinstance(member, dict) or member.get("type") != "way":
            continue
        geometry = member.get("geometry")
        if not isinstance(geometry, list) or len(geometry) < 2:
            continue
        points = [
            [float(point["lon"]), float(point["lat"])]
            for point in geometry
            if isinstance(point, dict) and "lon" in point and "lat" in point
        ]
        if len(points) >= 2:
            lines.append(points)
    return lines


def main() -> None:
    data = json.loads(INPUT_PATH.read_text(encoding="utf-8"))
    rows = []
    for element in data.get("elements", []):
        if element.get("type") != "relation":
            continue
        osm_id = int(element["id"])
        tags = element.get("tags") if isinstance(element.get("tags"), dict) else {}
        name = tags.get("name") or FALLBACK_NAMES.get(osm_id) or f"relation{osm_id}"
        lines = member_lines(element)
        if not lines:
            print(f"relation {osm_id}（{name}）无可用成员几何，跳过")
            continue
        rows.append((osm_id, name, Json(lines)))

    if not rows:
        raise RuntimeError("没有解析出任何行政区边界，检查 district_boundaries.json")

    with psycopg2.connect(**connection_parameters()) as connection:
        with connection.cursor() as cursor:
            returned = execute_values(
                cursor,
                UPSERT_SQL,
                rows,
                template="(%s::bigint, %s::varchar, %s::jsonb)",
                page_size=len(rows),
                fetch=True,
            )

    print(f"解析 relation {len(rows)} 个，入库 {len(returned)} 个：")
    for osm_id, name, npoints, area_km2 in returned:
        print(f"  {osm_id}  {name}  顶点 {npoints}  面积 {area_km2} km²")


if __name__ == "__main__":
    main()
