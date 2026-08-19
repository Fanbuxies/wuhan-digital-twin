# 武汉数字孪生 Demo

武汉中心城区（江岸/江汉/硚口/汉阳/武昌/青山/洪山七区）建筑白模三维底座 + 楼宇物联网设备监测演示项目。

## 技术栈

- **后端**：Java 17 + Spring Boot 3.2 + MyBatis-Plus 3.5 + PostgreSQL 14/PostGIS 3
- **前端**：Vue 3 + TypeScript + Vite 5 + Pinia + Element Plus + Cesium 1.115
- **数据准备**：Python 3.10 + psycopg2

## 快速启动

### 1. 环境准备

- JDK 17+
- Node.js 18+
- Docker Desktop（用于 PostGIS 容器）
- Python 3.10+（数据准备脚本）

### 2. 启动数据库

```bash
# 复制环境变量模板并填写密码
cp .env.example .env
# 编辑 .env，设置 POSTGRES_PASSWORD

# 启动 PostGIS 容器（映射到宿主 5434 端口）
docker-compose up -d

# 初始化表结构
# （容器内自动执行 backend/src/main/resources/db/schema.sql，或手动导入）
```

### 3. 启动后端

```bash
cd backend
mvn clean install
mvn spring-boot:run
# 后端运行在 http://localhost:8080
# Swagger UI: http://localhost:8080/swagger-ui.html
```

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
# 前端运行在 http://localhost:5173
```

### 5. 数据准备（可选）

```bash
cd data-prep
pip install -r requirements.txt

# 拉取 OSM 建筑数据并入库
python fetch_osm.py
python load_to_pg.py

# 生成 3D Tiles
# （需 Docker 镜像 geodan/pg2b3dm，见 docs/runbook.md）

# 播种设备点位
python seed_devices.py
```

## 目录结构

```
├── backend/              后端 Spring Boot 工程
│   ├── src/main/java/    Java 源码（按模块分包：building/device/facility/alarm/stat）
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── db/schema.sql  数据库表结构
│   │   └── mapper/        MyBatis XML
│   └── logs/             运行日志（.gitignore）
├── frontend/             前端 Vue 3 + Cesium 工程
│   ├── src/
│   │   ├── api/          后端接口封装
│   │   ├── components/   Vue 组件
│   │   ├── stores/       Pinia 状态管理
│   │   ├── utils/        工具函数与 Cesium 图层封装
│   │   └── views/        页面视图
│   └── public/tiles/     3D Tiles 切片（.gitignore）
├── data-prep/            数据准备脚本
│   ├── scripts/          Python 脚本按功能分组
│   ├── sql/              建表/分区维护 SQL
│   ├── logs/             脚本日志
│   └── output/           OSM 原始数据缓存（.gitignore）
├── docs/                 项目文档
│   ├── requirements.md   接口清单与业务需求
│   ├── architecture.md   架构说明
│   ├── runbook.md        运维手册
│   └── tasks/archive/    历史任务书归档
├── pgdata/               PostGIS 数据卷（.gitignore）
├── .claude/              Claude Code 配置（hooks/rules）
├── docker-compose.yml
├── .env                  环境变量（.gitignore，见 .env.example）
└── README.md             本文件
```

## 核心功能

- **建筑白模底座**：中心城区七区 29818 栋建筑，按用途分色、按高度分明度
- **立面程序化增强**：CustomShader 绘制楼层横带/窗格/屋顶，按用途区分立面样式
- **设备点位监测**：2000 台设备（烟感/水浸/温湿度/电气/摄像头），实时状态 WebSocket 推送
- **市政设施监测**：充电桩/路灯/井盖/公交站，独立图层与告警
- **点选交互**：建筑/设备/设施点选高亮 + 属性面板
- **管理端**：建筑/设备列表 + CRUD + 点行联动定位

## 常见问题

**Q: 前端白屏/建筑不可点选？**
A: 检查 `frontend/public/tiles/tileset.json` 是否存在；若缺失，需先跑 `pg2b3dm` 生成 3D Tiles（见 `docs/runbook.md`）

**Q: 后端日志暴涨？**
A: 模拟器每 3 秒写一次实时状态，日志级别 INFO 会打每条心跳。生产环境建议改 `logback-spring.xml` 或把模拟器日志降到 DEBUG

**Q: 分区维护怎么跑？**
A: Windows 计划任务 `WuhanTwin-PartitionMaintenance` 每日 03:17 自动补建分区；手动清理过期分区见 `docs/runbook.md`

## 开发约定

- **分层规约**：严格 Controller → Service → Mapper，详见 `CLAUDE.md` 和 `.claude/rules/java-alibaba.md`
- **空间操作**：PostGIS 函数处理，禁止在 Java 侧做几何运算
- **前端图层**：设备/设施走 Primitive API（BillboardCollection），不用 Entity 循环 add
- **Git 工作流**：功能分支开发，PR 合并；不直接推 main

## 许可证

MIT
