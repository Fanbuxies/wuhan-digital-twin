# 项目开发约束

## 技术栈
- 后端：Java 17 + Spring Boot 3.2 + MyBatis-Plus 3.5 + PostgreSQL 14/PostGIS 3
- 前端：Vue 3 + TypeScript + Vite 5 + Pinia + Element Plus + Cesium 1.115
- 数据准备：Python 3.10 + psycopg2

## 通用规则
- 每次任务只创建/修改我明确指定的文件，禁止重构无关代码
- 禁止自行新增依赖，需要新依赖时先向我确认
- 所有代码注释使用中文
- 新增或修改接口时，同步更新 docs/requirements.md 的接口清单章节

## 目录结构
```
wuhan-digital-twin/
├── README.md              项目说明（快速启动、目录索引、FAQ）
├── .editorconfig          编辑器规范（LF/CRLF、缩进）
├── .env.example           环境变量模板
├── .env                   实际密码（.gitignore）
├── docker-compose.yml     PostGIS 容器定义
├── backend/               后端 Spring Boot
│   ├── src/main/java/com/wuhan/twin/
│   │   ├── building/      建筑模块（controller/service/mapper/entity/vo/dto）
│   │   ├── device/        设备模块
│   │   ├── facility/      市政设施模块
│   │   ├── alarm/         告警模块
│   │   ├── stat/          统计模块
│   │   ├── simulator/     模拟器 @Scheduled
│   │   ├── websocket/     WebSocket 推送
│   │   ├── config/        全局配置（CORS/Swagger/Jackson）
│   │   └── common/        通用组件（统一返回/异常处理/分页）
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── db/schema.sql  表结构
│   │   └── mapper/        MyBatis XML
│   └── logs/              运行日志（.gitignore，按天滚动，保留 15 天）
├── frontend/              前端 Vue 3 + Cesium
│   ├── src/
│   │   ├── api/           后端接口封装
│   │   ├── components/    Vue 组件（BuildingPanel/DevicePanel/AdminDrawer）
│   │   ├── stores/        Pinia 状态管理（building/device/facility/admin）
│   │   ├── utils/
│   │   │   ├── cesium/    Cesium 图层封装（tilesetLayer/deviceLayer/buildingLayer/facadeShader/viewer）
│   │   │   └── *.ts       工具函数（设备图标/WebSocket）
│   │   ├── views/         页面视图（TwinView）
│   │   ├── router/        路由配置
│   │   └── main.ts        入口
│   └── public/tiles/      3D Tiles 切片（.gitignore，pg2b3dm 生成）
├── data-prep/             数据准备脚本
│   ├── scripts/
│   │   ├── fetch/         OSM 数据拉取（fetch_osm.py/fetch_facilities.py）
│   │   ├── load/          入库脚本（load_to_pg.py/load_districts.py/load_facilities.py）
│   │   ├── seed/          设备播种（seed_devices.py）
│   │   └── ops/           运维脚本（run_partition_maintenance.ps1 每日分区维护）
│   ├── sql/               SQL 脚本（build_3d.sql 生成三维几何/partition_maintenance.sql 分区维护）
│   ├── logs/              脚本日志（.gitignore，按天一个文件）
│   ├── output/            OSM 原始数据缓存（.gitignore）
│   └── requirements.txt   Python 依赖
├── docs/
│   ├── requirements.md    接口清单与业务需求
│   ├── architecture.md    架构说明（技术选型/数据流/模块分层/关键设计）
│   ├── runbook.md         运维手册（环境变量/后端操作/数据库分区/3D Tiles 生成/常见问题）
│   └── tasks/archive/     历史任务书归档
├── pgdata/                PostGIS 数据卷（.gitignore）
├── .claude/               Claude Code 配置（hooks/rules）
└── .trash/                本地回收站（删除前暂存）
```

**关键路径**：
- **后端入口**：`backend/src/main/java/com/wuhan/twin/TwinApplication.java`
- **前端入口**：`frontend/src/main.ts`
- **建筑底座加载**：`frontend/src/utils/cesium/tilesetLayer.ts`
- **设备图层渲染**：`frontend/src/utils/cesium/deviceLayer.ts`
- **立面 Shader**：`frontend/src/utils/cesium/facadeShader.ts`
- **分区维护调度**：Windows 计划任务 `WuhanTwin-PartitionMaintenance` 每日 03:17 调用 `data-prep/scripts/ops/run_partition_maintenance.ps1`
- **3D Tiles 生成**：`docs/runbook.md` → 「3D Tiles 生成」章节

## Java 编码规约
- 写 Java / SQL / MyBatis 代码前必须阅读 @.claude/rules/java-alibaba.md（阿里巴巴 Java 开发手册·黄山版【强制】级条款），冲突时以该文件为准

## 后端约束
- Controller 统一返回 `R<T>`，禁止直接返回实体或 Map
- 分层严格：controller → service → mapper，Controller 禁止注入 Mapper
- entity 与 vo/dto 分离，entity 不出 service 层
- 异常统一由 GlobalExceptionHandler 处理，业务异常抛 BizException
- 空间字段用 PostGIS 函数处理，禁止在 Java 侧做几何运算

## 本机环境坑位
- **Git Bash 跑 docker 必须禁用 MSYS 路径转换**。`-v 宿主路径:/app/output` 里的
  `/app/output` 会被 Git Bash 当成 Unix 路径翻译成 Git 安装目录，
  导致产物写到 `D:/Soft/Git/app/output/` 而不是挂载目录。两种写法：
  ```
  MSYS_NO_PATHCONV=1 docker run -v "D:/Code/wuhan-digital-twin/frontend/public/tiles:/app/output" ...
  docker run -v "D:/Code/wuhan-digital-twin/frontend/public/tiles://app/output" ...
  ```
  用 PowerShell 跑 docker 没有此问题。
- pg2b3dm 走镜像 `geodan/pg2b3dm`（本机没装 dotnet），容器内连库用主机名
  `twin-pg:5432`，需加 `--network wuhan-digital-twin_default`。
- PostGIS 容器 `twin-pg` 映射 **5434**，库/用户均为 `twin`。密码在 User 作用域
  环境变量 `PGPASSWORD`（当前 shell 的 `$env:PGPASSWORD` 是空的），
  取法 `[Environment]::GetEnvironmentVariable('PGPASSWORD','User')`，
  禁止明文写入任何文件或命令回显。
- 8080 / 5173 可能由另一个会话的 preview 托管，不要自己跑
  `mvn spring-boot:run` 抢端口，需要重启后端先说一声。

## 前端约束
- 一律 `<script setup lang="ts">`
- Cesium Viewer 单例管理，禁止在组件内直接 new Viewer
- 设备点位渲染必须用 Primitive API（BillboardCollection / LabelCollection），
  禁止 Entity 循环 add —— 设备数超 200 时 Entity 会导致逐帧重建卡顿
- 建筑点选用 scene.pick 获取 Cesium3DTileFeature，通过 setColor 高亮，
  禁止销毁重建 tileset
- 全局只注册一个 ScreenSpaceEventHandler，按 picked 对象类型分发
- API 请求统一走 src/api/request.ts 封装
