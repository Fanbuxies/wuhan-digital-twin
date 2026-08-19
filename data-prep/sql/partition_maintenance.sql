-- t_device_telemetry 分区维护脚本
-- 【调度状态】第 1 步（补建分区）已接每日自动调度：Windows 计划任务
--   WuhanTwin-PartitionMaintenance 每日 03:17 调 data-prep/run_partition_maintenance.ps1，
--   该脚本内联了与下方第 1 个 DO 块等价的建区逻辑，日志落 data-prep/logs/。
--   第 2 步（DROP 过期分区）仍需手动执行本文件：自动删表属于无人确认的 DDL，
--   与项目红线冲突，故刻意不纳入调度。磁盘吃紧时人工跑一次本文件即可。
-- 1) 补建未来分区，保证今天起 8 天（今天 + 未来 7 天）已就绪（按 Asia/Shanghai 自然日切分）。
--    预建 7 天以上是为了脚本漏跑时新数据也不会落回 default 分区；
--    早先只预建 1 天，是担心开区间查询（ts >= now() - interval '1 day'）会因命中
--    空的未来分区而扫全表，实测该顾虑不成立：now() 是 stable 而非 immutable 函数，
--    分区裁剪在执行期（而非计划期）完成，空的未来分区代价可忽略。
--    注：自动调度走的是 run_partition_maintenance.ps1，那里 future_days 已提到 14 留出容错窗口；
--    本 .sql 保持 7 是因为它同时也是人工兜底入口，与下方保留期 7 天配对更直观。
-- 2) 保留期 7 天：DROP 早于 7 天前的按天分区（DROP，不是 DELETE）
-- 预建天数：今天 + FUTURE_DAYS 天。人工执行时与保留期 7 天对齐
DO $$
DECLARE
    d date;
    d_start timestamptz;
    d_end timestamptz;
    part_name text;
    future_days integer := 7;
BEGIN
    FOR d IN
        SELECT generate_series(
            (now() AT TIME ZONE 'Asia/Shanghai')::date,
            (now() AT TIME ZONE 'Asia/Shanghai')::date + future_days,
            interval '1 day'
        )::date
    LOOP
        d_start := (d::text || ' 00:00:00+08')::timestamptz;
        d_end   := ((d + 1)::text || ' 00:00:00+08')::timestamptz;
        part_name := 't_device_telemetry_' || to_char(d, 'YYYYMMDD');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF t_device_telemetry FOR VALUES FROM (%L) TO (%L)',
            part_name, d_start, d_end
        );
    END LOOP;
END $$;

DO $$
DECLARE
    r record;
    cutoff date := (now() AT TIME ZONE 'Asia/Shanghai')::date - 7;
BEGIN
    FOR r IN
        SELECT c.relname
        FROM pg_class c
        JOIN pg_inherits i ON i.inhrelid = c.oid
        JOIN pg_class p ON p.oid = i.inhparent
        WHERE p.relname = 't_device_telemetry'
          AND c.relname ~ '^t_device_telemetry_[0-9]{8}$'
          AND to_date(substring(c.relname from '[0-9]{8}$'), 'YYYYMMDD') < cutoff
    LOOP
        EXECUTE format('ALTER TABLE t_device_telemetry DETACH PARTITION %I', r.relname);
        EXECUTE format('DROP TABLE %I', r.relname);
        RAISE NOTICE '已删除过期分区 %', r.relname;
    END LOOP;
END $$;
