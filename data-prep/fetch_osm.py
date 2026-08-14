import json
import time
from pathlib import Path

import requests


OVERPASS_ENDPOINTS = (
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)
OVERPASS_QUERY = """[out:json][timeout:180];
way["building"](30.540,114.283,30.595,114.345);
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
