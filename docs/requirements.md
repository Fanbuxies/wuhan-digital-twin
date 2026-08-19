# 武汉武昌白模三维底座 + 楼宇物联网监测 Demo 需求说明

## 1. 项目定位
以武汉市武昌江滩及昙华林一带为范围，构建建筑白模三维底座，
叠加楼宇物联网设备点位，实时展示设备状态与告警，
支持点选建筑查属性、点选设备看历史曲线。

范围 bbox：west=114.283, south=30.540, east=114.375, north=30.625
（含长江两岸江滩、昙华林老城与临江大道武昌段沿线，全域 5303 栋，含 48 栋 relation 多边形楼宇综合体）
坐标系：WGS84 / EPSG:4326（全链路统一，禁止混用高德/百度 GCJ-02 或 BD-09 底图）

## 2. 数据来源与高度派生
建筑轮廓来自 OpenStreetMap Overpass API，取 way["building"] 与 relation["building"]（多边形楼宇综合体，仅取外环，内院暂不挖洞）。
高度按优先级派生，并必须记录来源字段 height_source：

1. tags.height 存在 → 直接使用，height_source = 'osm_height'
2. tags["building:levels"] 存在 → levels * 3.2，height_source = 'osm_levels'
3. 均无 → 按 tags.building 类型取默认值，height_source = 'default_by_type'
   residential/apartments 30，office 40，commercial 24，retail 18，
   industrial 12，school 15，其他 15

height 有效区间 3~300 米，超出则回退到默认值逻辑。

室外市政设施（充电桩/路灯/井盖/公交站）数据来源分两路：
1. 充电桩、公交站取 OSM 实际标注：node["amenity"="charging_station"]、
   node["highway"="bus_stop"]、node["public_transport"="platform"]["bus"="yes"]，source = 'osm'
2. 路灯、井盖 OSM 覆盖度极差，改为沿道路中心线插值生成，source = 'road_interp'：
   先把 way["highway"~"^(trunk|primary|secondary|tertiary|residential)$"] 落 t_road，
   再用 ST_LineInterpolatePoint 按间距取点。间距不写死，由目标数量反推
   （spacing = 总里程 / target，并受最小间距兜底），
   避免 OSM 道路里程未知导致点位爆量：路灯 target 800（最小间距 30 米）、
   井盖 target 300（最小间距 100 米）。几何运算全在 PostGIS 侧，Python 只做参数与批次。

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

### t_road 道路中心线（仅用于插值生成路灯/井盖点位）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| osm_id | bigint UNIQUE | OSM way id |
| name | varchar(128) | |
| road_type | varchar(32) NOT NULL | OSM highway 标签值 |
| geom | geometry(LineString,4326) NOT NULL | GIST 索引 |
| length_m | numeric(10,2) NOT NULL | ST_Length(geom::geography) |
| created_at | timestamptz | |

### t_facility 室外市政设施
| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| facility_code | varchar(64) UNIQUE | |
| facility_name | varchar(128) | 插值生成的点无名称，为 null |
| facility_type | varchar(32) NOT NULL | CHARGING_PILE/STREET_LAMP/MANHOLE/BUS_STOP，CHECK 约束 |
| osm_id | bigint | OSM 直取的点有值，插值生成的为 null |
| road_id | bigint | 插值生成的点记录所属道路，OSM 直取的为 null |
| location | geometry(Point,4326) NOT NULL | GIST 索引 |
| altitude | numeric(6,2) DEFAULT 0 | 相对地面高度，路灯 6 米，其余 0 |
| status | varchar(16) NOT NULL | ONLINE/OFFLINE/FAULT，CHECK 约束 |
| source | varchar(16) NOT NULL | osm/road_interp，CHECK 约束 |
| install_time | timestamptz | |
| created_at | timestamptz | |

设施不建 building_id，与 t_device 是两套独立台账：t_device 的点由
ST_GeneratePoints 落在建筑轮廓内且 building_id NOT NULL，
市政设施在室外、不属于任何楼宇，故不复用 t_device 而另立一表，
也不为此放宽 t_device 既有的 CHECK 与非空约束。

### t_device_realtime 实时状态（一监测对象一行，UPSERT）
object_type varchar(16) NOT NULL DEFAULT 'DEVICE'（DEVICE/FACILITY，CHECK 约束），
device_id bigint，metrics jsonb NOT NULL，
alarm_level smallint DEFAULT 0（0正常 1预警 2告警），update_time timestamptz，
主键为复合键 (object_type, device_id)

### t_device_telemetry 历史遥测（按 ts 范围分区）
object_type varchar(16) NOT NULL DEFAULT 'DEVICE'，
id bigserial，device_id bigint，metrics jsonb，ts timestamptz NOT NULL

### t_alarm 告警
id bigserial PK，object_type varchar(16) NOT NULL DEFAULT 'DEVICE'，
device_id bigint，alarm_type varchar(32)，
alarm_level smallint，alarm_value jsonb，
status varchar(16)（PENDING/CONFIRMED/CLOSED），
occur_time timestamptz，close_time timestamptz

上面三张表由设备与设施共用。t_device.id 与 t_facility.id 都是从 1 起的 bigserial，
直接混存必然撞键，故加 object_type 判别列显式区分，而不用「id 偏移量」这类隐式约定。
列名保持 device_id 不改为 object_id：**device_id 表示被监测对象的主键，
它指向 t_device 还是 t_facility 由同行的 object_type 决定**；
改名要牵动前后端十余处 DO/VO/mapper/store，属未被要求的重构。
凡按 device_id 查这三张表，都必须同时带 object_type 条件，
UPSERT 的冲突目标也必须写成 (object_type, device_id) —— 复合主键之后
device_id 单列已无唯一索引，只写 ON CONFLICT (device_id) 会直接报错。

## 4. 接口清单
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/building/tileset-info | 3D Tiles 地址 + 建筑总数 + 初始视角参数；未生成 3D Tiles 时 tilesetUrl 为 null，前端走 GeoJSON 降级 |
| GET | /api/building/{id} | 建筑详情（含 heightSource、中心点经纬度、轮廓 GeoJSON）；建筑不存在返回 404 |
| GET | /api/building/geojson?bbox= | 降级方案，GeoJSON FeatureCollection；bbox 必填（west,south,east,north），缺省或非法返回参数错误——全域已扩至中心城区七区近 3 万栋，降级路径只服务当前视野；条数上限由 app.building.geojson-max-features 控制 |
| GET | /api/building/page?current=&size=&keyword=&buildingType= | 建筑分页（管理端列表），current 从 1 起、size 默认 20 上限 500；keyword 按名称模糊匹配；记录含中心点 lon/lat，不含 footprint 几何 |
| GET | /api/device/list?buildingId=&type= | 设备列表（含经纬度、altitude），两参数均选填 |
| GET | /api/device/page?current=&size=&keyword=&deviceType=&status=&buildingId= | 设备分页（管理端列表），current/size 同上；keyword 按名称或编号模糊匹配；记录含 deviceTypeLabel；deviceType/status 取值非法时返回参数错误 |
| GET | /api/device/{id}/realtime | 单设备实时值；设备尚无实时数据（离线设备、模拟器未跑过）返回 404 |
| GET | /api/device/{id}/history?from=&to=&metric= | 历史序列 |
| GET | /api/facility/list?type=&bbox= | 市政设施列表（含经纬度、altitude），两参数均选填；type 非四类枚举值之一返回参数错误，bbox 需为 west,south,east,north 四个数值 |
| GET | /api/facility/page?current=&size=&keyword=&facilityType=&status= | 设施分页（管理端列表），current/size 语义同建筑分页；keyword 按名称或编号模糊匹配；记录含 facilityTypeLabel；facilityType/status 取值非法时返回参数错误 |
| POST | /api/building | 新增建筑（管理端写操作），请求体 name/buildingType/levels/height/heightSource/lon/lat；footprint 由服务端按中心点生成约 20 米见方近似矩形，center 为中心点；heightSource 取值非法返回参数错误 |
| PUT | /api/building/{id} | 编辑建筑，整体更新表单可编辑字段并按新中心点重建几何；建筑不存在返回 404；osm_id 等数据管线字段不受影响 |
| DELETE | /api/building/{id} | 删除建筑；建筑不存在返回 404，建筑下仍有设备时返回业务错误拒绝删除 |
| POST | /api/device | 新增设备（管理端写操作），请求体 deviceCode/deviceName/deviceType/buildingId/floor/altitude/status/lon/lat；location 由服务端按点位构造；编号重复、建筑不存在、类型/状态非法均返回错误；在线设备会被模拟器自动接管 |
| PUT | /api/device/{id} | 编辑设备，整体更新表单可编辑字段并按新点位重建 location；设备不存在返回 404；编号重复时返回业务错误 |
| DELETE | /api/device/{id} | 删除设备，事务内连带清理该设备的实时状态（t_device_realtime）、告警（t_alarm）与历史遥测（t_device_telemetry），均带 object_type='DEVICE' 限定 |
| GET | /api/facility/{id}/realtime | 单设施实时值，复用 DeviceRealtimeVO（objectType 为 FACILITY）；尚无实时数据返回 404 |
| GET | /api/alarm/page?level=&status= | 告警分页 |
| POST | /api/alarm/{id}/confirm | 告警确认 |
| GET | /api/stat/overview | 概览指标（设备总数/在线数/告警数 + 设施总数/在线数/告警数），设备三项只统计 object_type='DEVICE'，设施三项只统计 FACILITY |
| WS | /ws/realtime | 服务端每 3 秒推送一次全量在线设备快照、每 6 秒推送一次全量在线设施快照，新增告警逐条单独推送 |

WebSocket 推送体格式固定：
```json
{"type":"DEVICE_UPDATE","data":[{"deviceId":1,"objectType":"DEVICE","metrics":{},"alarmLevel":2,"ts":""}]}
{"type":"FACILITY_UPDATE","data":[{"deviceId":1,"objectType":"FACILITY","metrics":{},"alarmLevel":1,"ts":""}]}
{"type":"ALARM_NEW","data":{"deviceId":1,"objectType":"DEVICE","alarmType":"SMOKE_ALARM","alarmLevel":2,"alarmValue":{},"status":"PENDING","occurTime":""}}
```

设施快照走独立的 FACILITY_UPDATE 类型而非塞进 DEVICE_UPDATE 靠字段判别，
前端两个 store 直接按 type 分发，不必在回调里过滤。
告警只有 ALARM_NEW 一种类型，前端按 objectType 决定交给设备还是设施 store。
注意 FACILITY_UPDATE 与 ALARM_NEW 的 deviceId 在设施场景下是 t_facility.id，
与 t_device.id 的取值范围重叠，判别只能靠 objectType。

## 5. 前端图层职责
- 建筑层（3D Tiles 就绪后）：Cesium3DTileset + Cesium3DTileStyle，
  按「用途色相 × 高度明度」双通道分色（8 类 × 3 档共 24 条样式条件，归并表见下）；
  点选通过 scene.pick 获取 Cesium3DTileFeature，setColor 高亮，
  取消高亮按同一取色函数还原配色；
  场景照明用固定方向光与时钟解耦（夜间太阳在地平线下会把白模压黑），
  tileset.lightColor 显式保持中性，方向光强度 1.2 防过曝，
  背光面环境亮度底由自定义球谐系数提供（imageBasedLightingFactor 被 Cesium
  硬校验为 [0,1] 不能放大，环境项改走 sphericalHarmonicCoefficients）；
  白模全不透明（alpha 1.0）
- 建筑立面程序化细节（3D Tiles 路径）：Cesium3DTileset.customShader
  （MODIFY_MATERIAL，只在样式色/高亮色上乘明暗系数，不覆盖配色）。
  切片无 UV（顶点属性仅 POSITION/NORMAL/_FEATURE_ID_0），贴图路封死，
  楼层横带、竖向窗格、屋顶区分全部由模型坐标 + 法线在片元着色器程序化生成；
  楼层数一律 height/3.2 推算（levels 仅 19% 覆盖不可用），
  楼层线按片元椭球高从各楼楼底起算（抛物面近似与 Cesium 精确大地高毫米级一致），
  屋顶按法线判定不画窗格并整体略暗；
  底层约 4 m 按底商整片通透处理（不画竖向窗格，过渡带平滑恢复）；
  立面样式按用途族区分：商业（commercial/retail/hotel/office/louge/yes;retail）
  横向幕墙分格（分格周期更宽、玻璃面占比更大、层线更明显），
  住宅（apartments/house/residential/dormitory/bungalow/appartment）规则小窗
  （周期更窄、窗柱更宽），其余用途走通用窗格；
  用途族判定不走 fsInput.metadata——Cesium 1.115 管线不把 b3dm 批表
  （property table）暴露进 shader（实测编译报 no such field in structure），
  改为按片元收到的样式色精确匹配（样式色由「用途 × 高度」唯一决定，
  24 色常量由取色函数派生，与样式同源）；
  匹配目标须按 PBR 电介质折算：切片 metallic=0，Cesium 的
  czm_pbrMetallicRoughnessMaterial 输出 diffuse = 样式色 ×(1−f0)×(1−metallic)，
  电介质 f0=0.04，故片元收到的是样式色 × 0.96（实测 128,191,216 → 123,183,207）；
  场景 highDynamicRange=false 时 czm_gammaCorrect 为空操作，样式色以原始 sRGB 到达片元，
  不需要 gamma 解码；被高亮的建筑样式色被高亮色覆盖，
  立面临时退为通用窗格；
  GeoJSON 降级路径无 customShader 能力，保持纯色，不做立面图案
- 建筑层（当前 GeoJSON 降级路径）：GeoJsonDataSource 加载 /api/building/geojson，
  该接口已要求必带 bbox，前端按相机 computeViewRectangle 取当前视野范围，
  算不出矩形时回落到七区采集范围兜底；
  clampToGround 关闭，逐 Feature 设 polygon.height = baseAltitude、
  polygon.extrudedHeight = baseAltitude + height（extrudedHeight 是绝对高程）；
  与 tileset 共用同一套「用途 × 高度」取色函数，outline 关闭（5300 栋逐栋描边会掉帧）；
  scene.pick 拿到的是 Entity 而非 Cesium3DTileFeature，
  高亮只能改 polygon.material 颜色并缓存原色以便还原
- 建筑用途归并表（OSM building 标签 42 种取值归并为 8 类，色相编码用途；
  明度按高度分 3 档：≤24m 浅 / ≤80m 本色 / >80m 深）：

  | 归并类 | 基色 | OSM 原始值 | 数量 |
  |---|---|---|---|
  | 住宅 | #d9b382 | apartments, house, residential, dormitory, bungalow, appartment | 1662 |
  | 商业 | #4aa3c7 | commercial, retail, hotel, office, louge, yes;retail | 328 |
  | 教育 | #8e7cc3 | university, school, college, kindergarten, library, museum | 217 |
  | 医疗 | #d47b9a | hospital, clinic | 39 |
  | 工业仓储 | #8a8574 | industrial, greenhouse, barn, water_tower | 28 |
  | 交通市政 | #6b8fa3 | parking, carport, train_station, guardhouse, gatehouse | 42 |
  | 公共文体 | #5fae94 | public, sports_hall, grandstand, stadium, church, cathedral, theatre, pavilion, community | 49 |
  | 未分类 | #c2c8ce | yes, roof, ruins, tower | 2938 |
- 设备层：BillboardCollection + LabelCollection（Primitive API），
  状态变更只改 billboard.image / color，禁止 Entity；
  图标用 canvas 生成 dataURL 并按「设备类型 + 状态」组合缓存（5 类型 × 5 状态最多 25 张），
  禁止每帧或每次推送重新生成；
  billboard.id 打成 { kind: 'device', deviceId } 结构供拾取分发，
  disableDepthTestDistance 设为无穷让点位不被楼体遮挡，
  label 用 translucencyByDistance 远距淡出（超千个标签常显会糊屏）
- 设备图标双通道编码：形状编码类型（任何状态不变形）、填充色编码状态
  （正常态取类型色，离线/故障/预警/告警被灰 #909399 / 橙 #e6a23c / 黄 #f9b115 / 红 #e55353 覆盖）；
  类型形状与配色：

  | 类型 | 形状 | 正常态色 |
  |---|---|---|
  | SMOKE 烟感 | 三角形 | #8e44ad 紫 |
  | WATER 水浸 | 圆形 | #1e88e5 蓝 |
  | TEMP_HUMI 温湿度 | 菱形 | #17a2b8 青 |
  | ELECTRIC 电气 | 方形 | #5c6bc0 靛 |
  | CAMERA 摄像头 | 六边形 | #d6336c 品红 |

- 设施层：与设备层同构但集合独立（各自一个 BillboardCollection + LabelCollection），
  图标同样按「设施类型 + 状态」缓存 dataURL（4 类型 × 5 状态最多 20 张），
  billboard.id 打成 { kind: 'facility', facilityId }，状态变更只改 billboard.image；
  图标同设备层双通道编码：形状按类型区分（充电桩圆角方形、路灯五边形、井盖竖胶囊、
  公交站顶弧方牌），正常态取类型色（绿 #2f9e44 / 棕褐 #8d6e63 / 青绿 #0ca678 / 紫罗兰 #845ef7），
  异常态被状态色覆盖；
  两处与设备层刻意相反：
  ① disableDepthTestDistance 取 0（完全参与深度测试）——
     设施都在地面，若照设备层关掉深度检测，江对岸的路灯会浮在近处楼顶上；
     代价是俯视角下贴地设施会被楼体挡住，这是符合物理直觉的取舍
  ② 路灯与井盖不建 Label——两者数量在千级且没有可读名称，
     全部挂标签必然糊屏，只有充电桩与公交站建 Label；
     标签淡出区间比设备层收紧一档（800 m 内可见，2000 m 外全透明）
- 告警层：独立 PrimitiveCollection，告警时叠加扩散圆动画
- 拾取：全局单一 ScreenSpaceEventHandler，按对象类型分发，分支共 3 条；
  顺序为设备 → 设施 → 建筑：设备浮在楼体之上故最先判，
  设施虽会被楼体遮挡但仍是图元，需先于建筑面判定，
  三条分支互斥（命中任一分支即清掉另两个面板的选中态）
- 实时通道：WebSocket 单例，断线指数退避重连（1 s 起，上限 30 s），主动关闭不重连；
  vite proxy 需为 /ws 配 ws: true 才会转发 Upgrade 头

## 6. 模拟器
后端 @Scheduled(fixedRateString = "${app.simulator.fixed-rate}") 默认 3 秒一轮，
仅遍历 status = 'ONLINE' 的设备（离线与故障设备不产生实时数据），按类型生成合理波动值。
告警按 app.simulator.alarm-probability（默认 0.002，即每设备每轮 0.2%）触发，
同一设备已存在 PENDING 告警时本轮只生成正常值，避免反复刷同一条告警——
按 docs 早期设想的 5% 算，228 台设备每分钟会产生上百条告警，t_alarm 一小时即上万行。
每轮 UPSERT t_device_realtime，每 app.simulator.telemetry-tick-interval 轮
（默认 20，即 60 秒）批量落一次 t_device_telemetry，避免历史表暴涨。
t_device_telemetry 按 ts 天粒度实际分区（RANGE 分区，非仅 DEFAULT），
只保留最近 7 天，通过 DROP 老分区实现（不用 DELETE）；
维护脚本 data-prep/partition_maintenance.sql 需手动重复执行，
提前建好未来至少 7 天的分区，避免新数据落回 default。
通过配置项 app.simulator.enabled 开关，关闭后连调度线程都不创建，
实时表停止刷新但 /api/device/{id}/realtime 仍可读到最后一次快照。

设施模拟是独立的一个 @Scheduled 任务，与设备任务共用 app.simulator.enabled 开关，
节奏 app.simulator.facility-fixed-rate 默认 6000 毫秒（6 秒一轮，设施数量约为设备两倍，
放慢一档以减半写库压力），告警概率 app.simulator.facility-alarm-probability 默认 0.001，
低于室内设备的 0.002——室外市政设施故障率本就更低。
同一设施已存在 PENDING 告警时本轮只生成正常值，与设备逻辑一致。
四类设施的指标与告警形态：

| 类型 | 正常 metrics | 告警形态 | alarmType | level |
|---|---|---|---|---|
| CHARGING_PILE 充电桩 | power 0~60 kW、plugged 0/1、temperature 20~45 ℃ | temperature 70~95 | CHARGE_FAULT | 2 |
| STREET_LAMP 路灯 | brightness 60~100 %、energy 0.05~0.3 kWh | brightness = 0 | LAMP_OFF | 1 |
| MANHOLE 井盖 | tilt 0~3 °、waterLevel 0~20 cm | tilt 15~40 | MANHOLE_TILT | 2 |
| BUS_STOP 公交站 | passengerFlow 0~40 人/时、screenOn 1 | screenOn = 0 | BUS_STOP_OFFLINE | 1 |

t_facility 为空表时设施模拟器空转（只打 debug 日志不刷 warn），
接口返回空数组，前端图层不建任何图元，状态卡显示 0 —— 数据未导入不影响其余功能。

## 7. 实施顺序
0. 落盘需求与开发约束（本文档 + AGENTS.md）
1. 数据准备：Overpass 拉建筑 → PostGIS
2. 后端骨架 + 建筑/设备 REST
3. 前端 Cesium + GeoJSON extrudedHeight 拉伸（快速验证白模效果）
4. pg2b3dm 生成 3D Tiles，前端切换 Cesium3DTileset
5. 设备图层 + 模拟器 + WebSocket
6. 告警列表 + 历史曲线 + 联动定位
