# 架构说明

## 系统概览

本项目是一个轻量级数字孪生演示系统，以武汉中心城区建筑白模为底座，叠加楼宇物联网设备实时监测。

```
┌─────────────┐
│   浏览器     │  Vue 3 + Cesium 1.115
│  localhost:  │  • 3D Tiles 建筑底座
│    5173     │  • BillboardCollection 设备点位
└──────┬──────┘  • WebSocket 实时推送
       │ HTTP + WS
       ↓
┌─────────────┐
│  Spring Boot │  Java 17 + Spring Boot 3.2
│   Backend    │  • REST API (Swagger)
│  localhost:  │  • WebSocket 推送
│    8080     │  • 模拟器 @Scheduled
└──────┬──────┘
       │ JDBC
       ↓
┌─────────────┐
│  PostgreSQL  │  PostGIS 3 (容器)
│   + PostGIS  │  • 空间索引 GIST
│  localhost:  │  • 按天分区 (遥测表)
│    5434     │  • ST_* 几何函数
└─────────────┘
```

## 技术选型理由

| 组件 | 选型 | 理由 |
|---|---|---|
| 3D 引擎 | Cesium 1.115 | 开源 WebGL 地球引擎，3D Tiles 原生支持，适合大规模建筑底座 |
| 前端框架 | Vue 3 + TypeScript | 响应式、组合式 API、类型安全；与 Cesium 非 React 生态契合度高 |
| 后端框架 | Spring Boot 3.2 | 成熟生态、MyBatis-Plus 简化 CRUD、WebSocket 开箱即用 |
| 空间数据库 | PostGIS 3 | PostgreSQL 空间扩展，ST_Extrude / ST_Transform / 空间索引完备 |
| 3D Tiles 生成 | pg2b3dm | 开源 dotnet 工具，直连 PostGIS 生成隐式瓦片树，无需 Blender |

## 数据流

### 1. 建筑底座渲染

```
OSM Overpass API
  ↓ (Python: fetch_osm.py)
t_building (Polygon + 派生高度)
  ↓ (SQL: ST_Extrude)
t_building_3d (PolyhedralSurface, EPSG:32650)
  ↓ (Docker: pg2b3dm -a id,name,...)
frontend/public/tiles/*.glb (EXT_structural_metadata)
  ↓ (Vite 静态托管)
Cesium3DTileset.fromUrl('/tiles/tileset.json')
```

### 2. 实时监测推送

```
@Scheduled fixedRate=3s
  ↓
DeviceSimulateTask (模拟 2000 台设备指标)
  ↓ UPSERT
t_device_realtime (单表 2000 行)
  ↓ 每 20 tick (60s)
t_device_telemetry (按天分区)
  ↓ WebSocket /ws/realtime
前端 BillboardCollection.get(id).image = 新图标
```

### 3. 点选交互

```
scene.pick(screenPos)
  ↓ 若命中 Cesium3DTileFeature
feature.getProperty('id')
  ↓ GET /api/building/{id}
BuildingPanel 显示属性 (名称/高度/来源)
```

## 模块分层

### 后端

```
com.wuhan.twin
├── building      建筑 CRUD + GeoJSON + tileset-info
├── device        设备 CRUD + 实时值 + 历史序列
├── facility      市政设施 CRUD + 实时值
├── alarm         告警分页 + 确认
├── stat          概览统计
├── simulator     模拟器 @Scheduled
├── websocket     推送 /ws/realtime
├── config        全局配置 (CORS / Swagger / Jackson)
└── common        统一返回 R<T> / 异常处理 / 分页封装
```

每个模块内：
```
controller/   参数校验、VO 组装、@RestController
service/      业务逻辑、@Transactional (仅此层)
mapper/       MyBatis-Plus BaseMapper 接口 + XML
entity/       DO (数据库映射)
vo/           返回前端的视图对象
dto/          接收前端的传输对象
```

### 前端

```
src/
├── api/           axios 封装 + 接口方法
├── components/    BuildingPanel / DevicePanel / AdminDrawer
├── stores/        Pinia (building / device / facility / admin)
├── utils/         
│   ├── cesium/    图层封装 (tilesetLayer / deviceLayer / viewer)
│   └── *.ts       工具函数 (request / buildingLayer)
├── views/         TwinView (主场景)
├── router/        路由配置
└── main.ts        入口
```

## 关键设计

### 1. 建筑着色：用途色相 × 高度明度

8 类用途 × 3 档高度（≤24m 浅 / ≤80m 本色 / >80m 深）= 24 条 Cesium3DTileStyle 条件。色板在 `buildingLayer.ts:BUILDING_CATEGORY_RULES` 定义，tileset 和 GeoJSON 降级路径共用。

### 2. 立面程序化增强：CustomShader

Cesium 1.115 的 `customShader` (MODIFY_MATERIAL) 在片元着色器绘制楼层横带/窗格/屋顶：
- 楼层数 = `height / 3.2`（levels 覆盖率仅 19%）
- 楼层线从各楼楼底起算（椭球高 → 抛物面近似，精度毫米级）
- 用途族判定按**样式色精确匹配**（metadata 在 Cesium 1.115 管线里拿不到）
- 商业幕墙 vs 住宅小窗 vs 通用窗格三套参数

### 3. 设备图层：Primitive API

2000 台设备若用 `Entity` 循环 add 会逐帧重建卡顿。改用：
- `BillboardCollection` + `LabelCollection` 单集合承载全部点位
- 图标 dataURL 按「类型 + 状态」缓存（5 类 × 5 状态最多 25 张）
- `disableDepthTestDistance: Infinity` 让点位不被楼体遮挡
- `translucencyByDistance` + `DistanceDisplayCondition` 远处淡出 + 真正不渲染

### 4. 分区策略：遥测表按天 RANGE 分区

`t_device_telemetry` 每分钟 1818 行，7 天约 180 万行。`PARTITION BY RANGE (ts)`：
- 计划任务每日补建未来 14 天分区
- 手动清理 7 天前分区（`DETACH` + `DROP TABLE`，非 DELETE）
- `now()` 是 stable 函数 → 分区裁剪在执行期完成，空的未来分区代价可忽略

### 5. WebSocket 推送协议

固定三种消息类型：
```json
{"type":"DEVICE_UPDATE","data":[{deviceId,objectType:"DEVICE",metrics,alarmLevel,ts}]}
{"type":"FACILITY_UPDATE","data":[{deviceId,objectType:"FACILITY",...}]}
{"type":"ALARM_NEW","data":{deviceId,objectType,alarmType,alarmLevel,...}}
```
`objectType` 判别设备还是设施（两者 id 范围重叠）。前端按 `type` 分发到对应 store。

## 性能指标

- **建筑数**：29818 栋
- **设备数**：2000 台（在线约 1818）
- **FPS**：50-60（Chrome，RTX 3060）
- **WebSocket 载荷**：245 KB / 3 s = 82 KB/s/client
- **数据库**：730 MB（pgdata，含 7 天遥测 + 空间索引）
- **3D Tiles**：63 MB（81 glb + 29 subtree）

## 扩展性

- **建筑扩容**：当前七区 29818 栋；扩到武汉三镇约 15 万栋，tiles 约 300 MB，Cesium 隐式瓦片树自适应
- **设备扩容**：当前 2000 台；扩到 1 万台需调 `LabelCollection` 淡出阈值 + 分区预建天数
- **多租户**：当前单租户；需加 `tenant_id` 列 + Row-Level Security
- **历史回放**：当前只有 7 天遥测；需改保留期 + 加时间轴控件

## 已知限制

- **GeoJSON 降级路径**：七区扩面后全域 GeoJSON 已超 12000 条上限，改为强制带 bbox
- **建筑 UV 缺失**：glTF 只有 POSITION/NORMAL，立面图案全程序化，无法贴真实纹理
- **离线设备不产生数据**：模拟器只遍历 `status='ONLINE'` 的设备
- **WebSocket 单向推送**：前端只收不发，无心跳 ping/pong
