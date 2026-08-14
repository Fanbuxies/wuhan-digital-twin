# 武汉光谷白模三维底座 + 楼宇物联网监测 Demo 需求说明

## 1. 项目定位
以武汉市武昌江滩及昙华林一带为范围，构建建筑白模三维底座，
叠加楼宇物联网设备点位，实时展示设备状态与告警，
支持点选建筑查属性、点选设备看历史曲线。

范围 bbox：west=114.283, south=30.540, east=114.345, north=30.595
（含长江两岸江滩与昙华林老城，全域 2884 栋）
坐标系：WGS84 / EPSG:4326（全链路统一，禁止混用高德/百度 GCJ-02 或 BD-09 底图）

## 2. 数据来源与高度派生
建筑轮廓来自 OpenStreetMap Overpass API，仅取 way["building"]。
高度按优先级派生，并必须记录来源字段 height_source：

1. tags.height 存在 → 直接使用，height_source = 'osm_height'
2. tags["building:levels"] 存在 → levels * 3.2，height_source = 'osm_levels'
3. 均无 → 按 tags.building 类型取默认值，height_source = 'default_by_type'
   residential/apartments 30，office 40，commercial 24，retail 18，
   industrial 12，school 15，其他 15

height 有效区间 3~300 米，超出则回退到默认值逻辑。

## 3. 数据库表结构

### t_building 建筑
| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| osm_id | bigint UNIQUE | OSM way id |
| name | varchar(128) | |
| building_type | varchar(32) | OSM building 标签值 |
| levels | int | 层数 |
| height | numeric(6,2) NOT NULL | 建筑高度（米） |
| height_source | varchar(24) NOT NULL | osm_height/osm_levels/default_by_type |
| base_altitude | numeric(6,2) DEFAULT 0 | 地面基准高程 |
| footprint | geometry(Polygon,4326) NOT NULL | GIST 索引 |
| center | geometry(Point,4326) | GIST 索引 |
| created_at | timestamptz | |

### t_device 设备
| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| device_code | varchar(64) UNIQUE | |
| device_name | varchar(128) | |
| device_type | varchar(32) | SMOKE/WATER/TEMP_HUMI/ELECTRIC/CAMERA |
| building_id | bigint FK | 关联 t_building |
| floor | int | 楼层 |
| location | geometry(Point,4326) | |
| altitude | numeric(6,2) | 相对地面高度，用于三维定位 |
| status | varchar(16) | ONLINE/OFFLINE/FAULT |
| install_time | timestamptz | |

### t_device_realtime 实时状态（一设备一行，UPSERT）
device_id bigint PK FK，metrics jsonb NOT NULL，
alarm_level smallint DEFAULT 0（0正常 1预警 2告警），update_time timestamptz

### t_device_telemetry 历史遥测（按 ts 范围分区）
id bigserial，device_id bigint，metrics jsonb，ts timestamptz NOT NULL

### t_alarm 告警
id bigserial PK，device_id bigint，alarm_type varchar(32)，
alarm_level smallint，alarm_value jsonb，
status varchar(16)（PENDING/CONFIRMED/CLOSED），
occur_time timestamptz，close_time timestamptz

## 4. 接口清单
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/building/tileset-info | 3D Tiles 地址 + 初始视角参数；未生成 3D Tiles 时 tilesetUrl 为 null，前端走 GeoJSON 降级 |
| GET | /api/building/{id} | 建筑详情（含 heightSource、中心点经纬度、轮廓 GeoJSON）；建筑不存在返回 404 |
| GET | /api/building/geojson?bbox= | 降级方案，GeoJSON FeatureCollection；bbox 选填（west,south,east,north），缺省返回全域，条数上限由 app.building.geojson-max-features 控制 |
| GET | /api/device/list?buildingId=&type= | 设备列表（含经纬度、altitude），两参数均选填 |
| GET | /api/device/{id}/realtime | 单设备实时值；设备尚无实时数据（离线设备、模拟器未跑过）返回 404 |
| GET | /api/device/{id}/history?from=&to=&metric= | 历史序列 |
| GET | /api/alarm/page?level=&status= | 告警分页 |
| POST | /api/alarm/{id}/confirm | 告警确认 |
| GET | /api/stat/overview | 概览指标（设备总数/在线数/告警数） |
| WS | /ws/realtime | 服务端每 3 秒推送一次全量在线设备快照，新增告警逐条单独推送 |

WebSocket 推送体格式固定：
```json
{"type":"DEVICE_UPDATE","data":[{"deviceId":1,"metrics":{},"alarmLevel":2,"ts":""}]}
{"type":"ALARM_NEW","data":{"deviceId":1,"alarmType":"SMOKE_ALARM","alarmLevel":2,"alarmValue":{},"status":"PENDING","occurTime":""}}
```

## 5. 前端图层职责
- 建筑层（3D Tiles 就绪后）：Cesium3DTileset + Cesium3DTileStyle，按 ${height} 分色；
  点选通过 scene.pick 获取 Cesium3DTileFeature，setColor 高亮
- 建筑层（当前 GeoJSON 降级路径）：GeoJsonDataSource 加载 /api/building/geojson，
  clampToGround 关闭，逐 Feature 设 polygon.height = baseAltitude、
  polygon.extrudedHeight = baseAltitude + height（extrudedHeight 是绝对高程）；
  按 height 分 5 段配色，outline 关闭（905 栋逐栋描边会掉帧）；
  scene.pick 拿到的是 Entity 而非 Cesium3DTileFeature，
  高亮只能改 polygon.material 颜色并缓存原色以便还原
- 设备层：BillboardCollection + LabelCollection（Primitive API），
  状态变更只改 billboard.image / color，禁止 Entity；
  图标用 canvas 生成 dataURL 并按「设备类型 + 状态」组合缓存（5 类型 × 5 状态最多 25 张），
  禁止每帧或每次推送重新生成；
  billboard.id 打成 { kind: 'device', deviceId } 结构供拾取分发，
  disableDepthTestDistance 设为无穷让点位不被楼体遮挡，
  label 用 translucencyByDistance 远距淡出（250 个标签常显会糊屏）
- 告警层：独立 PrimitiveCollection，告警时叠加扩散圆动画
- 拾取：全局单一 ScreenSpaceEventHandler，按对象类型分发；
  设备分支在建筑分支之前判定（点位浮在楼体之上）
- 实时通道：WebSocket 单例，断线指数退避重连（1 s 起，上限 30 s），主动关闭不重连；
  vite proxy 需为 /ws 配 ws: true 才会转发 Upgrade 头

## 6. 模拟器
后端 @Scheduled(fixedRateString = "${app.simulator.fixed-rate}") 默认 3 秒一轮，
仅遍历 status = 'ONLINE' 的设备（离线与故障设备不产生实时数据），按类型生成合理波动值。
告警按 app.simulator.alarm-probability（默认 0.002，即每设备每轮 0.2%）触发，
同一设备已存在 PENDING 告警时本轮只生成正常值，避免反复刷同一条告警——
按 docs 早期设想的 5% 算，228 台设备每分钟会产生上百条告警，t_alarm 一小时即上万行。
每轮 UPSERT t_device_realtime，每 app.simulator.telemetry-tick-interval 轮
（默认 5，即 15 秒）批量落一次 t_device_telemetry，避免历史表暴涨。
通过配置项 app.simulator.enabled 开关，关闭后连调度线程都不创建，
实时表停止刷新但 /api/device/{id}/realtime 仍可读到最后一次快照。

## 7. 实施顺序
0. 落盘需求与开发约束（本文档 + AGENTS.md）
1. 数据准备：Overpass 拉建筑 → PostGIS
2. 后端骨架 + 建筑/设备 REST
3. 前端 Cesium + GeoJSON extrudedHeight 拉伸（快速验证白模效果）
4. pg2b3dm 生成 3D Tiles，前端切换 Cesium3DTileset
5. 设备图层 + 模拟器 + WebSocket
6. 告警列表 + 历史曲线 + 联动定位
