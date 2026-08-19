# 阶段 3 阻塞：等待用户授权执行批量写库

**状态**：等待用户决策，非技术故障。代码侧已就绪。

## 卡在哪

`t_device` 仍是旧的 732 台，只分布在 4/24 网格，验收不通过。
播种脚本已改好并 dry-run 验证，但**执行需要用户授权**：

- `central-districts-task.md` 第六章：批量数据操作先给用户看 SQL，等确认再执行
- 用户全局规约：DELETE/UPDATE 必须先确认影响行数

Stop 钩子只认最终数字，不认"这一步在等人拍板"，因此会持续拦截。
写这份文件是为了让会话能正常结束，不空转烧 token。

## 已完成（实测）

| 项 | 实测值 | 验收要求 | 状态 |
|---|---|---|---|
| t_building | 29818 | 29000~38000 | 通过 |
| t_building_3d | 29818 | = t_building | 通过 |
| t_district | 7 | 7 | 通过 |
| 区外建筑 | 0 | 0 | 通过 |
| default 分区 | 0 行 | 0 | 通过 |
| 库大小 | 600 MB | ≤5 GB | 通过（原 1348 MB） |
| telemetry-tick-interval | 20 | 20 | 通过 |
| t_device | 732 | 1900~2000 | **待播种** |
| 设备网格覆盖 | 4/24 | ≥8 | **待播种** |

`seed_devices.py` 已修复配额逻辑（详见文件内注释），
dry-run 结果：**正好 2000 台，覆盖 18/24 网格**。

## 需要用户定的两件事

### 1. 批准播种 + 清理孤儿设备

```bash
cd data-prep && python seed_devices.py
```

脚本用 `ON CONFLICT (device_code) DO UPDATE`，只更新不删除。
旧 732 台中仅 257 台的 device_code 会被复用，剩 **475 台**成为孤儿，
直接跑完是 2475 行，超过 2000 上限。清理 SQL：

```sql
-- 1) 确认影响行数（只读，期望 475）
SELECT count(*) FROM t_device
WHERE install_time < (SELECT max(install_time) FROM t_device);

-- 2) 删除关联实时状态行（t_device_realtime 无外键）
DELETE FROM t_device_realtime
WHERE device_id IN (
    SELECT id FROM t_device
    WHERE install_time < (SELECT max(install_time) FROM t_device)
);

-- 3) 删除孤儿设备
DELETE FROM t_device
WHERE install_time < (SELECT max(install_time) FROM t_device);

-- 4) 复核：期望 2000
SELECT count(*) FROM t_device;
```

### 2. 孤儿设备的历史遥测怎么处理

删设备后，`t_device_telemetry` 里 250 万行会有一部分变成孤儿。
按任务书 5.2 可整表清空重建（纯模拟数据、无代码读取），
也可只删对应 device_id 的行。**需用户选一个。**

## 另一个待办（不阻塞，纯只读检查）

遥测分区只建到 **2026-08-19**（今天）。
明天数据会落回 default 分区，届时 `default_rows > 0` 会重新触发拦截。
`data-prep/partition_maintenance.sql` 已存在，需确认是否有定期执行机制、
以及是否提前建足 7 天分区。

## 解除方式

用户授权后，删掉本文件，钩子恢复拦截。
