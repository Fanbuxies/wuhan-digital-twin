-- t_device_telemetry 分区维护脚本：手动重复执行，暂不接定时调度
-- 1) 补建未来分区，保证至少未来 7 天已就绪（按 Asia/Shanghai 自然日切分）
-- 2) 保留期 7 天：DROP 早于 7 天前的按天分区（DROP，不是 DELETE）

DO $$
DECLARE
    d date;
    d_start timestamptz;
    d_end timestamptz;
    part_name text;
BEGIN
    FOR d IN
        SELECT generate_series(
            (now() AT TIME ZONE 'Asia/Shanghai')::date,
            (now() AT TIME ZONE 'Asia/Shanghai')::date + 7,
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
