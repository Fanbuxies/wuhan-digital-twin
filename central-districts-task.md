# 任务：数据范围扩展至武汉中心城区（决策已定版）

> 给终端 Claude Code 的任务书，整份贴过去即可，无需额外上下文。
> 【已验证】= 我在本机实际跑命令、或向 Overpass 实际查询得到的数字，可直接信任。
> 执行本任务的模型**没有识图能力**，验收全部做成数字断言。

---

## 一、目标与已定参数

把现有能力（3D Tiles 建筑白模 + 用途配色 + 设备点位 + 实时监测 + 管理端）
从当前的武昌江滩一小片，扩展到**武汉中心城区七区**：
江岸、江汉、硚口、汉阳、武昌、青山、洪山。**不含新城区。**

**以下参数用户已拍板，不要再问、也不要自行改动：**

| 参数 | 定值 | 说明 |
|---|---|---|
| 设备总量 | **2000 台（实际入库数，不是脚本目标值）** | 不按建筑数线性放大，**不要超过 2000** |
| 遥测落库间隔 | **60 秒** | 即 `telemetry-tick-interval` 由 5 改为 **20**（3000ms × 20） |
| 遥测保留期 | **7 天** | 通过 DROP 老分区实现 |
| 遥测占用目标 | **稳定态 ≤ 5 GB** | 见 3.3 的测算 |

⚠️ **注意 `seed_devices.py` 会超出目标值约 22%**
（【已验证】现在 `TARGET_DEVICE_TOTAL = 600`，实际入库 **732** 台）。
所以要落到 2000 台，要么把 `TARGET_DEVICE_TOTAL` 设成 **约 1650**，
要么修正 cell 配额逻辑让实际值贴近目标。**验收看的是 `t_device` 的实际行数。**

⚠️ **降频只改历史落库频率，不动实时链路。**
`t_device_realtime` 仍按 `fixed-rate` 3 秒刷新、WebSocket 仍按 3 秒推送，
前端图标闪烁与告警即时性**必须保持不变**。改错地方会让实时画面变卡顿。

## 二、实测规模（已验证，直接用，不要再估算）

### 2.1 当前范围

【已验证】`data-prep/fetch_osm.py` 的 Overpass bbox：

```
(30.540, 114.283, 30.625, 114.375)
```

约 9.4 × 8.8 km ≈ **83 km²**，实际入库 **5303 栋**。

### 2.2 目标范围

采集用矩形 bbox（仅用于 Overpass 分块查询，
**最终范围以 5.1 的行政区边界裁剪为准**）：

```
(30.42, 114.05, 30.72, 114.47)
```

约 33 × 40 km ≈ **1300 km²**，是现在的 16 倍面积。

【已验证】我向 Overpass 实际查询过该 bbox 的建筑总数：

```
总数 38213（ways 38045 + relations 168）
```

即 **7.2 倍于现在的 5303 栋**。真实数字，非估算。

### 2.3 建筑侧资源外推——完全不是瓶颈

| 项 | 当前 | 扩展后（×7.2） |
|---|---|---|
| `t_building` | 5.8 MB | ~42 MB |
| `t_building_3d` | 6.3 MB | ~45 MB |
| 3D Tiles 产物 | 13 MB | ~95 MB |
| pg2b3dm 耗时 | 2.2 秒 | 20~60 秒 |

## 三、真正的瓶颈：遥测表

### 3.1 现状

【已验证】当前数据库 1348 MB，其中：

```
t_device_telemetry_default   6,463,718 行   1299 MB   ← 占全库 96%
t_device_realtime                  690 行     17 MB
其余所有表合计                              ~30 MB
```

【已验证】行宽约 **210 字节/行**（含堆 + 两个索引）。

### 3.2 两个已查明的结构性问题

**问题一：分区配了但没建。**
【已验证】`t_device_telemetry` 确实按 `RANGE (ts)` 分区，但唯一子分区是
`t_device_telemetry_default`（DEFAULT），**从未创建任何按时间的实际分区**，
646 万行全堆在默认分区。后果：分区裁剪不生效，且**无法用 DROP 分区做保留**。

**问题二：这张表目前无人读取。**
【已验证】`DeviceTelemetryMapper.xml` 里**只有 `batchInsert` 和 `deleteByDeviceId`，
没有任何 SELECT**。`/api/device/{id}/history` 在 `docs/requirements.md` 里有记录，
但 `DeviceController` 从未实现该接口。

也就是说这 1.3 GB 写进去之后从没被读过一次，表上还挂着
`idx_t_device_telemetry_device_ts` 这个专为未实现接口准备的索引。

**索引保留不动**——history 接口迟早要做，砍了还得加回来。

### 3.3 目标配置下的容量测算

60 秒间隔 = 每台每天 1440 行，按当前在线率约 89%：

| 设备数 | 在线数 | 行数/天 | 占用/天 | **7 天封顶** |
|---|---|---|---|---|
| **2000（本任务目标）** | ~1780 | 256 万 | 540 MB | **~3.7 GB** |

【已验证】D 盘可用 437 GB，因此**加上保留策略后容量完全不是问题**。

**关键认知**：真正解决问题的是「7 天保留」这个天花板，不是降频。
没有天花板时，每天写多少都会在时间足够长后撑爆；有了天花板，
占用变成有界常数。验收阈值定在 **5 GB**（预期 3.7 GB + 索引膨胀与 vacuum 滞后的余量）；
若明显超出，说明降频或保留策略其中之一没真正生效。

## 四、分阶段方案

> **阶段 0 必须最先做完并单独汇报**，否则后面每一步都在给漏水的桶加水。
> 其余每个阶段做完也要停下汇报，不要一口气推到底。

### 阶段 0：遥测分区改造 + 保留策略【前置，必做】

1. 为 `t_device_telemetry` 建立**按天的实际分区**
2. **清空现有 646 万行后按新结构重建**（决策见 5.2，SQL 先给用户看）
3. 建立**保留策略**：只保留最近 **7 天**，通过 DROP 老分区实现，**不要用 DELETE**
4. 提供**可重复执行的分区维护脚本**：提前建好未来若干天的分区
   （建议至少提前 7 天），避免新数据又落回 default 分区
5. 把 `application.yml` 的 `app.simulator.telemetry-tick-interval` 由 **5 改为 20**

⚠️ **所有 DDL 和批量数据操作，先把完整 SQL 给用户看，等确认再执行。**
⚠️ **禁止 DROP TABLE / TRUNCATE `t_device_telemetry`**（清空重建方案除外，
且必须先获用户明确同意）。

### 阶段 1：扩大 OSM 采集范围

改 `data-prep/fetch_osm.py` 的 bbox 为 2.2 节范围。
**同时按 5.1 拉取七个区的边界几何**（供阶段 2 裁剪用）。

⚠️ **不能沿用现在的单次查询**。现在是一条 `out geom;` 拉全量，
38213 栋的响应体积会到数百 MB，Overpass 大概率超时或被限流。

要求：

- 把 bbox **切成网格分块查询**（建议 4×4 或 5×5），逐块请求、逐块落盘
- 块间留间隔（Overpass 公共实例有速率限制），沿用现有多端点重试逻辑
- 支持**断点续传**：某块失败重跑时不必从头再来
- 合并时**按 OSM id 去重**（跨块边界的建筑会重复，relation 尤其容易重复）

### 阶段 2：入库、行政区裁剪与重新生成 3D Tiles

0. **先拉七区边界几何并落表**（见 5.1），后续裁剪依赖它
1. `load_to_pg.py` 扩面入库，沿用现有的 `ON CONFLICT (osm_id) DO UPDATE`（见 5.3），
   并在入库时用七区边界并集做 `ST_Intersects` 裁剪，
   **只保留落在中心城区内的建筑**
2. 重新生成 `t_building_3d`，**沿用 EPSG:32650 / UTM 50N + ST_Extrude**，
   **不要改坐标系**——之前踩过写成 32649 的坑
3. 重跑 pg2b3dm 生成切片，输出仍到 `frontend/public/tiles/`
4. 确认 `app.tileset.url` 指向路径不变，否则前端会回落到 GeoJSON 降级路径

### 阶段 3：设备扩容至 2000 台

`data-prep/seed_devices.py` 的 `TARGET_DEVICE_TOTAL` 当前是 600（实际入库 732，超出 22%）。

要求：

- 调整参数使**实际入库数落在 2000 上下、且不超过 2000**（注意上面说的 22% 超出），
  并确保**空间分布覆盖七个区**，不要仍然挤在原来那一小片
  （现有脚本已有 cell 网格配额逻辑 `MIN/MAX_BUILDINGS_PER_CELL`，
  扩面后需要复核这些参数在新范围下是否还合理）
- 复核 WebSocket 每 3 秒全量快照的**载荷大小**，把实测字节数报上来
- 若载荷过大，**先汇报再决定**是否改成增量推送，不要自行改推送协议

### 阶段 4：前端与接口适配

- **`app.building.geojson-max-features` 当前 12000**，38213 栋会超上限。
  GeoJSON 降级路径在全域下已不可行，改为：**必须带 bbox 参数、只服务当前视野**，
  全域请求直接拒绝或强制截断并打警告日志
- 复核管理端分页在 38k 行下的执行计划，确认 `building_type` / 关键字查询走了索引
- 重新设定 `app.tileset.camera` 初始视角，框住整个中心城区
- 若立面着色器任务已完成，需在 38k 栋规模下**重测帧率**
- `LabelCollection` 在 2000 个设备标签下的表现要实测，
  必要时按距离抽稀（现有 `translucencyByDistance` 可能不够）

## 五、三个关键决策——**用户已拍板，照做，不要再问**

### 5.1 范围裁剪：用行政区边界精确裁剪，不用粗矩形

矩形 bbox 只用于 Overpass 分块采集（分块查询必须用矩形），
**入库时必须用七区边界的并集做 `ST_Intersects` 裁剪**，
把落在蔡甸、江夏、东西湖等新城区的边角建筑剔掉。

【已验证】七个区的行政边界**在 OSM 里都有**，且都是 `admin_level=6`
（中国大陆的市辖区在 OSM 里是 6 不是 8，别写错），relation id 如下：

| 区 | OSM relation id |
|---|---|
| 江岸区 | 3077255 |
| 江汉区 | 3077256 |
| 硚口区 | 3077257 |
| 汉阳区 | 3076295 |
| 武昌区 | 3076297 |
| 青山区 | 3079613 |
| 洪山区 | 3080399 |

**不需要引入任何新数据源**——还是 Overpass、还是现有采集代码，
只是多拉一次这 7 个 relation 的几何。取边界的查询形如：

```
[out:json][timeout:120];
relation(id:3077255,3077256,3077257,3076295,3076297,3079613,3080399);
out geom;
```

边界几何建议单独落一张表（如 `t_district`，字段 `name` + `boundary geometry(MultiPolygon,4326)`），
入库建筑时按并集裁剪。**这张表是新增，建表 SQL 先给用户看。**

### 5.2 历史遥测：清空重建，不做迁移

**直接清空 `t_device_telemetry` 后按新分区结构重建**，不要逐分区迁移 646 万行。

理由（第三条是决定性的）：

1. 这是三天的**纯模拟数据**，无业务价值
2. 【已验证】**没有任何代码读过它**——mapper 里只有 `batchInsert` 和 `deleteByDeviceId`
3. 【已验证】**设备马上要重新播种**。`seed_devices.py` 用
   `ON CONFLICT (device_code) DO UPDATE`，从 732 台扩到 2000 台、
   分布范围完全改变后，新设备的 `device_code` 与 id 与现状对不上，
   **旧遥测的 `device_id` 会大面积变成指向不存在设备的孤儿行**

迁移 646 万行需要 `INSERT...SELECT`（临时翻倍占用 + 大量 WAL）或复杂的 ATTACH 操作，
为一堆即将失效的数据付这个代价不值得。

⚠️ 即便如此，**清空操作的 SQL 仍要先给用户看过再执行**。

### 5.3 建筑入库：按 osm_id UPSERT 增量合并

【已验证】`load_to_pg.py` **现在已经是 `ON CONFLICT (osm_id) DO UPDATE`**，
schema 里 `osm_id` 也有 UNIQUE 约束——**这条路本来就是通的，入库逻辑不用改**。

选它而非清空重灌的理由：

- **幂等可重跑**：38213 栋分块采集中途失败是大概率事件，UPSERT 允许直接重跑
- **没有空窗期**：清空重灌会有一段 `t_building` 为空的时间，那期间前端和管理端全是坏的
- **id 稳定**：现有 5303 栋主键不变，引用它们的地方不会断

现有 5303 栋全部落在新范围内，**不存在需要清理的范围外旧数据**。

## 六、硬约束

- **禁止自行新增依赖**，需要新依赖先问用户
- 数据库：DELETE / UPDATE 必须带 WHERE；**禁止 DROP TABLE / TRUNCATE**；
  所有 DDL 与批量数据操作**先给用户看 SQL，等确认再执行**
- 空间字段一律用 PostGIS 函数处理，**禁止在 Java 侧做几何运算**
- 后端分层严格：controller → service → mapper，统一返回 `R<T>`，
  Controller 禁止注入 Mapper
- Java 代码遵守 `.claude/rules/java-alibaba.md`
- 前端：Viewer 单例、设备点位走 Primitive API、禁止 Entity 循环、
  禁止销毁重建 tileset
- 新增或修改接口同步更新 `docs/requirements.md` 接口清单
- **禁止碰** `.claude/tmp/`、`pgdata/`、`.trash/`
- 不主动 commit / push

## 七、验收（数字断言，不依赖截图）

### 7.1 遥测分区（阶段 0，最重要）

```sql
-- 必须出现多个按天的实际分区，而不是只有 default
select c.relname, pg_get_expr(c.relpartbound, c.oid)
from pg_class c join pg_inherits i on i.inhrelid = c.oid
join pg_class p on p.oid = i.inhparent where p.relname = 't_device_telemetry';

-- 期望 0
select count(*) from t_device_telemetry_default;

-- 分区裁剪是否生效：应只扫到 1~2 个分区，而不是全表
explain select count(*) from t_device_telemetry where ts >= now() - interval '1 day';
```

配置断言：`application.yml` 的 `telemetry-tick-interval` **等于 20**。

### 7.2 数据规模

```sql
select count(*) from t_building;        -- Overpass 在矩形 bbox 内实测 38213，
                                        -- 经行政区裁剪 + 无效几何过滤后会少一些，
                                        -- 期望落在 30000 ~ 38000

-- 行政区裁剪是否真的生效：落在七区并集之外的建筑必须为 0
select count(*) from t_building b
where not exists (
  select 1 from t_district d where st_intersects(b.footprint, d.boundary)
);                                      -- 期望 0
select count(*) from t_building_3d;     -- 必须等于 t_building 行数
select count(*) from t_device;          -- 必须落在 1900 ~ 2000，不得超过 2000
select round(min(st_x(center))::numeric,3), round(min(st_y(center))::numeric,3),
       round(max(st_x(center))::numeric,3), round(max(st_y(center))::numeric,3)
from t_building;                        -- 四至必须接近 2.2 节 bbox
```

**设备空间分布检查**（防止全挤在原来那一小片）：

```sql
select width_bucket(st_x(location), 114.05, 114.47, 6) as col,
       width_bucket(st_y(location), 30.42, 30.72, 4) as row, count(*)
from t_device group by 1, 2 order by 1, 2;
```

期望：**多数网格都有设备**，不是只有原 bbox 那一两格有值。
（`location` 列名以实际 schema 为准。）

### 7.3 容量验证（跑满一段时间后回看）

```sql
select pg_size_pretty(pg_total_relation_size('t_device_telemetry')) as telemetry,
       pg_size_pretty(pg_database_size('twin')) as db;
select count(*) from t_device_telemetry;
```

按 3.3 测算，稳定态遥测应 **≤ 5 GB**（预期 3.7 GB）。
若明显超出，说明降频没生效或保留策略没跑。

### 7.4 接口与前端

- `curl /api/building/page?current=1&size=20` 的 `total` 等于建筑总数
- `curl /api/device/page?current=1&size=20` 的 `total` 落在 1900~2000
- `curl /api/building/tileset-info` 的 `tilesetUrl` 非 null，`buildingCount` 已更新
- `http://localhost:5173/tiles/tileset.json` 返回 200
- 页面里用 `window.__twinViewer` 取 tileset 统计：`numberOfTilesTotal > 0`，
  `scene.pick` 能拿到 `Cesium3DTileFeature`
- **WebSocket 单次推送载荷字节数**（阶段 3 要求实测的那个值）
- 帧率断言：平均 FPS **不低于 30**，低于则需调 LOD 或 `maximumScreenSpaceError`

### 7.5 汇报要求

把上述 SQL 与 curl 的**完整原始输出**贴给用户，不要只写「验证通过」。
画面观感由用户目视确认。

## 八、环境提醒

- 前端 `http://localhost:5173`，后端 `http://localhost:8080`，
  PostGIS 容器 `twin-pg` 映射 5434，库/用户均为 `twin`
- 密码在用户级环境变量 `PGPASSWORD`：
  `[Environment]::GetEnvironmentVariable('PGPASSWORD','User')`，
  当前 shell 的 `$env:PGPASSWORD` 是空的，必须显式从 User 作用域取；
  **禁止明文写入任何文件或命令回显**
- pg2b3dm 走 docker 镜像 `geodan/pg2b3dm`（本机没装 dotnet），
  容器内用主机名 `twin-pg:5432`，需 `--network wuhan-digital-twin_default`
- **8080 / 5173 目前由另一个会话的 preview 托管**，
  不要自己跑 `mvn spring-boot:run` 抢端口；需要重启后端先说一声
- Overpass 公共实例有速率限制，分块之间务必留间隔
- D 盘可用 437 GB，扩面后持续关注 `pgdata/` 增长
- 市政设施表 `t_facility` 当前为空。若后续播种设施，
  它与设备**共用 `t_device_telemetry`**，会额外增加写入量，届时需重算容量
