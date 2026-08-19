# 运维手册

本文档记录日常运维操作与常见坑位。

## 环境变量与密码管理

### 数据库密码

容器启动时从 `.env` 读取 `POSTGRES_PASSWORD`，宿主机脚本通过 **User 作用域环境变量** `PGPASSWORD` 访问：

```powershell
# 查看（PowerShell）
[Environment]::GetEnvironmentVariable('PGPASSWORD', 'User')

# 设置（PowerShell，重启终端后生效）
[Environment]::SetEnvironmentVariable('PGPASSWORD', 'your_password', 'User')
```

**禁止**明文写入脚本、任务定义或日志。

### Git Bash 下的 Docker 路径转换陷阱

Git Bash 会把 `-v /app/output` 自动翻译成 Git 安装目录（如 `D:/Soft/Git/app/output`），导致产物写错位置。两种解法：

```bash
# 方法 1：前置 MSYS_NO_PATHCONV=1
MSYS_NO_PATHCONV=1 docker run -v "D:/Code/...:/app/output" ...

# 方法 2：目标路径加前导双斜杠
docker run -v "D:/Code/.../tiles://app/output" ...
```

PowerShell 跑 docker 无此问题。

---

## 后端操作

### 启动与重启

**8080 / 5173 可能被另一个会话托管**。需要重启前先确认：

```powershell
# 查端口占用
Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue

# 停止进程（根据 PID）
Stop-Process -Id <pid> -Force

# 启动后端
cd backend
mvn clean spring-boot:run
```

### 日志管理

日志在 `backend/logs/`，按天滚动，保留 15 天（见 `logback-spring.xml`）。

**当天日志无上限**，模拟器每 3 秒写一批遥测会让日志暴涨（曾单日 4.6 GB）。生产环境建议：
- 把 `DeviceSimulateTask` / `DeviceRealtimeServiceImpl` 的心跳日志降到 DEBUG
- 或在 `logback-spring.xml` 加 `<maxFileSize>200MB</maxFileSize>` + `<totalSizeCap>3GB</totalSizeCap>`

清理老日志：
```bash
# 删除 7 天前的日志
find backend/logs -name "*.log" -mtime +7 -delete
```

---

## 数据库操作

### 连接参数

- 容器名：`twin-pg`
- 宿主端口：`5434`（容器内 5432）
- 库名/用户：`twin`
- 密码：见上方「环境变量」章节

连接串示例：
```bash
docker exec -it twin-pg psql -U twin -d twin
# 或从宿主机
psql -h localhost -p 5434 -U twin -d twin
```

### 分区维护

`t_device_telemetry` 按天分区，Windows 计划任务 `WuhanTwin-PartitionMaintenance` 每日 03:17 自动补建未来 14 天分区。

**手动触发**：
```powershell
Start-ScheduledTask -TaskName 'WuhanTwin-PartitionMaintenance'
```

**手动清理过期分区**（保留期 7 天）：
```bash
# 连进容器
docker exec -it -e PGPASSWORD twin-pg psql -U twin -d twin -f - <<'SQL'
\i /path/to/data-prep/sql/partition_maintenance.sql
SQL
```
或直接在宿主机执行 `data-prep/sql/partition_maintenance.sql` 的第二个 DO 块（DROP 语句）。

**分区状态检查**：
```sql
SELECT c.relname, pg_size_pretty(pg_total_relation_size(c.oid)) AS size
FROM pg_class c
JOIN pg_inherits i ON i.inhrelid = c.oid
JOIN pg_class p ON p.oid = i.inhparent
WHERE p.relname = 't_device_telemetry'
ORDER BY c.relname;
```

---

## 3D Tiles 生成

### 前置条件

- Docker 镜像 `geodan/pg2b3dm`
- `t_building_3d` 表已填充（执行 `data-prep/sql/build_3d.sql`）
- 输出目录 `frontend/public/tiles/` 为空或已备份

### 生成命令

```bash
export PGPASSWORD=$(powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('PGPASSWORD','User')" | tr -d '\r')

MSYS_NO_PATHCONV=1 docker run --rm \
  --network wuhan-digital-twin_default \
  -e PGPASSWORD \
  -v "D:/Code/wuhan-digital-twin/frontend/public/tiles:/app/output" \
  geodan/pg2b3dm \
  -h twin-pg -p 5432 -U twin -d twin \
  -t t_building_3d -c geom \
  -a id,name,height,building_type,levels,height_source \
  -o /app/output
```

**注意事项**：
- 容器内连 `twin-pg:5432`（不是宿主的 5434）
- `-a` 必须带，否则前端点选拿不到属性
- 网络 `wuhan-digital-twin_default` 由 `docker-compose.yml` 自动创建

生成后验证：
```bash
ls frontend/public/tiles/content/*.glb | wc -l   # 应为 81
ls frontend/public/tiles/tileset.json            # 应存在
```

---

## 前端操作

### 启动

```bash
cd frontend
npm install
npm run dev
# 访问 http://localhost:5173
```

### 硬刷新

浏览器会缓存 `tileset.json` 和 `.glb` 文件。重导切片后必须 **Ctrl+Shift+R 硬刷新**，否则前端仍加载旧切片。

### 构建生产版本

```bash
cd frontend
npm run build
# 产物在 frontend/dist/
```

---

## 数据准备脚本

### OSM 数据拉取与入库

```bash
cd data-prep
pip install -r requirements.txt

# 1. 拉取七区建筑数据（约 38k 栋，分块请求避开 Overpass 速率限制）
python scripts/fetch/fetch_osm.py

# 2. 入库 t_building，高度派生 + 裁剪到七区边界内
python scripts/load/load_to_pg.py

# 3. 生成 3D 几何（ST_Extrude）
docker exec -it -e PGPASSWORD twin-pg psql -U twin -d twin -f /path/to/sql/build_3d.sql

# 4. 生成 3D Tiles（见上方章节）
```

### 设备播种

```bash
cd data-prep
python scripts/seed/seed_devices.py
```
脚本按网格分配 2000 台设备，覆盖七区。`ON CONFLICT (device_code) DO UPDATE` 支持重复执行。

---

## 常见问题排查

### 前端白屏 / 建筑不可点选

**现象**：页面加载但 3D 场景空白，或建筑不能点选高亮。

**排查**：
1. F12 Console 看 404 —— `tileset.json` 或 `.glb` 缺失？
2. `frontend/public/tiles/` 是否为空？若是，需先生成 3D Tiles
3. 若切片存在但不可点选，检查 GLB 是否带 `EXT_mesh_features` 扩展：
   ```bash
   python -c "
   import json,struct
   b=open('frontend/public/tiles/content/3_3_3.glb','rb').read()
   clen,=struct.unpack('<I',b[12:16])
   j=json.loads(b[20:20+clen])
   print('extensionsUsed:',j.get('extensionsUsed'))
   "
   ```
   若返回 `None`，说明 pg2b3dm 跑时漏了 `-a`，需重导。

### 后端日志暴涨

见上方「后端操作 → 日志管理」。

### 分区 default 开始进数据

**现象**：`SELECT count(*) FROM t_device_telemetry_default;` 返回非 0。

**原因**：预建分区没跟上，新数据落回 default 分区，7 天保留上限失效。

**修复**：手动跑 `data-prep/sql/partition_maintenance.sql` 第一个 DO 块补建分区。

### Docker 容器连不上 / 探活超时

**现象**：脚本报 `容器不可用` 或 `pg_isready` 超时。

**排查**：
```bash
docker ps | grep twin-pg                      # 容器是否在跑
docker exec twin-pg pg_isready -U twin        # 容器内探活
docker logs twin-pg | tail -20                # 看容器启动日志
```

若容器死了：
```bash
docker-compose up -d
```

---

## 性能调优

### 遥测表膨胀

`t_device_telemetry` 每分钟写 1818 行（2000 设备 × 60 s / 20 tick），7 天约 180 万行 / 320 MB。按天分区 + 7 天 DROP 老分区可控。

若仍膨胀：
- 减少设备数（`seed_devices.py` 的 `TARGET_DEVICE_TOTAL`）
- 加大 tick 间隔（`application.yml` 的 `telemetry-tick-interval`）

### Cesium 帧率

- 29818 栋建筑 + 2000 设备 + 立面 shader，实测 50-60 FPS
- 若掉帧，检查 `LabelCollection` 数量 —— `deviceLayer.ts` 已做 `DistanceDisplayCondition` 裁剪
- 避免 `Entity` 循环 add（>200 逐帧重建）

---

## 备份与恢复

### 数据库备份

```bash
docker exec twin-pg pg_dump -U twin -d twin > backup_$(date +%Y%m%d).sql
```

### 恢复

```bash
docker exec -i twin-pg psql -U twin -d twin < backup_20260819.sql
```

### 3D Tiles 备份

重导前先备份（切片约 63 MB）：
```bash
mv frontend/public/tiles .trash/tiles_backup_$(date +%Y%m%d)
```
