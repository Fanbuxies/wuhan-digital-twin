import json
import time
from pathlib import Path

import requests


OVERPASS_ENDPOINTS = (
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)
# 与 fetch_osm.py 同一 bbox，保证设施点位落在已有建筑白模范围内
# 道路只取 trunk/primary/secondary/tertiary/residential 五级：更细的 service/footway 会把
# 插值出的路灯撒进小区内部通道与楼间小径，观感上不像市政路灯
OVERPASS_QUERY = """[out:json][timeout:180];
(
  node["amenity"="charging_station"](30.540,114.283,30.625,114.375);
  node["highway"="bus_stop"](30.540,114.283,30.625,114.375);
  node["public_transport"="platform"]["bus"="yes"](30.540,114.283,30.625,114.375);
  way["highway"~"^(trunk|primary|secondary|tertiary|residential)$"](30.540,114.283,30.625,114.375);
);
out geom;"""
OUTPUT_PATH = Path(__file__).resolve().parent / "output" / "osm_facilities.json"
# Overpass 公共实例近期频繁 504，重试次数与间隔都比 fetch_osm.py 更宽松
MAX_ATTEMPTS = 4
RETRY_INTERVAL_SECONDS = 15


def fetch_facility_data() -> dict:
    last_error = None
    for attempt in range(MAX_ATTEMPTS):
        endpoint = OVERPASS_ENDPOINTS[attempt % len(OVERPASS_ENDPOINTS)]
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
    # 拉不到就直接失败退出，不落半份数据、也不退化成假点位
    raise RuntimeError(f"OSM 市政设施数据拉取失败：{last_error}")


def main() -> None:
    data = fetch_facility_data()
    elements = data["elements"]
    node_count = sum(1 for element in elements if element.get("type") == "node")
    way_count = sum(1 for element in elements if element.get("type") == "way")
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(
        json.dumps(data, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    print(f"已保存 {len(elements)} 个要素（点 {node_count} 个 / 道路 {way_count} 条）到 {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
