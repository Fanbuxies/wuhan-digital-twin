import json
import time
from pathlib import Path

import requests


OVERPASS_ENDPOINTS = (
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)
# 中心城区七区范围。首版矩形（30.42,114.05~30.72,114.47）经 t_district 实际边界核对后
# 发现漏了汉阳区西侧、青山区东侧、洪山区南侧/东南侧，现按七区真实 bbox 外扩约 0.01~0.02 度余量重设。
# 矩形只用于分块采集，最终范围以行政区边界裁剪为准（见 load_to_pg.py 的 ST_Intersects 裁剪）
BBOX_SOUTH, BBOX_WEST, BBOX_NORTH, BBOX_EAST = 30.37, 113.96, 30.71, 114.64
# 单块查询约 38213/25 ≈ 1530 个要素，响应体积可控，不易触发 Overpass 超时/限流
GRID_ROWS = 5
GRID_COLS = 5
# 块间间隔：公共 Overpass 实例有速率限制，连续高频请求会被临时封禁
# 实测 8 秒仍会在第 3 块触发 429，抬到 30 秒
CHUNK_DELAY_SECONDS = 30
# 限流/网关超时后的退避重试：公共实例的 429 需要等待数十秒才会放行，
# 5 秒固定间隔不够，改为按 BACKOFF_SECONDS 逐级退避并轮换端点
MAX_ATTEMPTS = 6
BACKOFF_SECONDS = (20, 45, 90, 150, 240)

OUTPUT_DIR = Path(__file__).resolve().parent / "output"
CHUNKS_DIR = OUTPUT_DIR / "osm_chunks"
MERGED_PATH = OUTPUT_DIR / "osm_raw.json"
DISTRICT_PATH = OUTPUT_DIR / "district_boundaries.json"

# 中心城区七区，OSM 里均为 admin_level=6（中国大陆市辖区），relation id 已由用户核实
DISTRICT_RELATION_IDS = (3077255, 3077256, 3077257, 3076295, 3076297, 3079613, 3080399)


def fetch_with_retry(query: str) -> dict:
    last_error = None
    for attempt in range(MAX_ATTEMPTS):
        # 端点轮换：同一端点连续被限流时换另一个，避免卡在同一个实例的配额上
        endpoint = OVERPASS_ENDPOINTS[attempt % len(OVERPASS_ENDPOINTS)]
        try:
            response = requests.post(
                endpoint,
                data={"data": query},
                headers={"User-Agent": "wuhan-digital-twin-data-prep/1.0"},
                timeout=300,
            )
            response.raise_for_status()
            data = response.json()
            if not isinstance(data.get("elements"), list):
                raise ValueError("Overpass 响应缺少 elements 数组")
            return data
        except (requests.RequestException, ValueError) as error:
            last_error = error
            if attempt < MAX_ATTEMPTS - 1:
                wait_seconds = BACKOFF_SECONDS[min(attempt, len(BACKOFF_SECONDS) - 1)]
                # 服务端明确给了 Retry-After 就听它的
                retry_after = getattr(getattr(error, "response", None), "headers", {}) or {}
                header_wait = retry_after.get("Retry-After")
                if header_wait is not None and str(header_wait).isdigit():
                    wait_seconds = max(wait_seconds, int(header_wait))
                print(f"  第 {attempt + 1} 次拉取失败（{endpoint}），{wait_seconds} 秒后重试：{error}")
                time.sleep(wait_seconds)
    raise RuntimeError(f"Overpass 拉取失败：{last_error}")


def chunk_bboxes():
    lat_step = (BBOX_NORTH - BBOX_SOUTH) / GRID_ROWS
    lon_step = (BBOX_EAST - BBOX_WEST) / GRID_COLS
    for row in range(GRID_ROWS):
        for col in range(GRID_COLS):
            c_south = BBOX_SOUTH + row * lat_step
            c_north = BBOX_SOUTH + (row + 1) * lat_step
            c_west = BBOX_WEST + col * lon_step
            c_east = BBOX_WEST + (col + 1) * lon_step
            yield row, col, (c_south, c_west, c_north, c_east)


def fetch_chunk(row: int, col: int, bbox: tuple) -> bool:
    """返回 True 表示本次实际发起了网络请求（用于块间限流间隔判断）"""
    chunk_path = CHUNKS_DIR / f"chunk_{row}_{col}.json"
    if chunk_path.exists():
        print(f"分块 ({row},{col}) 已存在，跳过（断点续传）")
        return False
    south, west, north, east = bbox
    query = f"""[out:json][timeout:180];
(
  way["building"]({south},{west},{north},{east});
  relation["building"]({south},{west},{north},{east});
);
out geom;"""
    data = fetch_with_retry(query)
    chunk_path.write_text(
        json.dumps(data, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    print(f"分块 ({row},{col}) 已保存 {len(data['elements'])} 个要素")
    return True


def fetch_all_chunks() -> None:
    CHUNKS_DIR.mkdir(parents=True, exist_ok=True)
    chunks = list(chunk_bboxes())
    failed = []
    for row, col, bbox in chunks:
        try:
            requested = fetch_chunk(row, col, bbox)
        except RuntimeError as error:
            # 单块彻底失败不中断整轮：其余块继续拉，失败块留给下次重跑（断点续传）
            print(f"分块 ({row},{col}) 放弃：{error}")
            failed.append((row, col))
            time.sleep(CHUNK_DELAY_SECONDS)
            continue
        if requested:
            time.sleep(CHUNK_DELAY_SECONDS)
    if failed:
        raise RuntimeError(f"以下分块未成功，重跑本脚本会仅补这些块：{failed}")


def merge_chunks() -> None:
    """按 (type, id) 去重合并所有分块——跨块边界的建筑（尤其 relation）会在相邻块重复出现"""
    seen = set()
    merged_elements = []
    chunk_paths = sorted(CHUNKS_DIR.glob("chunk_*.json"))
    if len(chunk_paths) < GRID_ROWS * GRID_COLS:
        raise RuntimeError(
            f"分块不全（{len(chunk_paths)}/{GRID_ROWS * GRID_COLS}），先重跑 fetch_all_chunks 补齐再合并"
        )
    for chunk_path in chunk_paths:
        data = json.loads(chunk_path.read_text(encoding="utf-8"))
        for element in data.get("elements", []):
            key = (element.get("type"), element.get("id"))
            if key in seen:
                continue
            seen.add(key)
            merged_elements.append(element)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    MERGED_PATH.write_text(
        json.dumps({"elements": merged_elements}, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    print(f"合并去重后共 {len(merged_elements)} 个要素（来自 {len(chunk_paths)} 个分块），写入 {MERGED_PATH}")


def fetch_district_boundaries() -> None:
    if DISTRICT_PATH.exists():
        print(f"七区边界已存在于 {DISTRICT_PATH}，跳过（断点续传）")
        return
    ids = ",".join(str(i) for i in DISTRICT_RELATION_IDS)
    query = f"[out:json][timeout:120];\nrelation(id:{ids});\nout geom;"
    data = fetch_with_retry(query)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    DISTRICT_PATH.write_text(
        json.dumps(data, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    print(f"七区边界已保存 {len(data['elements'])} 个 relation 到 {DISTRICT_PATH}")


def main() -> None:
    fetch_all_chunks()
    merge_chunks()
    fetch_district_boundaries()


if __name__ == "__main__":
    main()
