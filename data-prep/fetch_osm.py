import json
import time
from pathlib import Path

import requests


OVERPASS_ENDPOINTS = (
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)
# relation["building"] 是带内院的 multipolygon 建筑，只查 way 会漏掉这类大院落综合体
# 北界与东界扩到 30.625/114.375，纳入临江大道武昌段（至二七长江大桥）沿线楼宇；
# 临江大道全长延伸到 114.415/30.655 的青山段，再扩会超出后端 geojson-max-features 上限，本轮不取
OVERPASS_QUERY = """[out:json][timeout:180];
(
  way["building"](30.540,114.283,30.625,114.375);
  relation["building"](30.540,114.283,30.625,114.375);
);
out geom;"""
OUTPUT_PATH = Path(__file__).resolve().parent / "output" / "osm_raw.json"
MAX_ATTEMPTS = 3
RETRY_INTERVAL_SECONDS = 5


def fetch_osm_data() -> dict:
    last_error = None
    for attempt in range(MAX_ATTEMPTS):
        endpoint = OVERPASS_ENDPOINTS[min(attempt, len(OVERPASS_ENDPOINTS) - 1)]
        try:
            response = requests.post(
                endpoint,
                data={"data": OVERPASS_QUERY},
                headers={"User-Agent": "wuhan-digital-twin-data-prep/1.0"},
                timeout=200,
            )
            response.raise_for_status()
            data = response.json()
            if not isinstance(data.get("elements"), list):
                raise ValueError("Overpass 响应缺少 elements 数组")
            return data
        except (requests.RequestException, ValueError) as error:
            last_error = error
            if attempt < MAX_ATTEMPTS - 1:
                print(f"第 {attempt + 1} 次拉取失败，{RETRY_INTERVAL_SECONDS} 秒后重试：{error}")
                time.sleep(RETRY_INTERVAL_SECONDS)
    raise RuntimeError(f"OSM 建筑数据拉取失败：{last_error}")


def main() -> None:
    data = fetch_osm_data()
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(
        json.dumps(data, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    print(f"已保存 {len(data['elements'])} 个 OSM 要素到 {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
