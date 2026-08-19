-- 重新生成 t_building_3d：沿用 EPSG:32650 / UTM 50N + ST_Extrude（不要改成 32649）
-- footprint 是 4326 地理坐标（单位度），height 单位米，必须先投影到米制坐标系再拉伸，
-- 否则会把「米」当「度」，楼高离谱到几十万米。
-- 幂等：按 id UPSERT，可重复执行；t_building 只增不删，故无需清理孤儿行

INSERT INTO t_building_3d (id, name, building_type, levels, height, height_source, geom)
SELECT
    b.id,
    b.name,
    b.building_type,
    b.levels,
    b.height,
    b.height_source,
    ST_Extrude(ST_Transform(b.footprint, 32650), 0, 0, b.height::double precision)
FROM t_building AS b
WHERE ST_IsValid(b.footprint)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    building_type = EXCLUDED.building_type,
    levels = EXCLUDED.levels,
    height = EXCLUDED.height,
    height_source = EXCLUDED.height_source,
    geom = EXCLUDED.geom;

-- 校验：行数应与 t_building 一致，Z 跨度应等于 height
SELECT
    (SELECT count(*) FROM t_building) AS building_count,
    (SELECT count(*) FROM t_building_3d) AS building_3d_count,
    (SELECT count(*) FROM t_building_3d WHERE abs(ST_ZMax(geom) - height::double precision) > 0.01) AS height_mismatch,
    (SELECT count(DISTINCT ST_SRID(geom)) FROM t_building_3d) AS srid_kinds,
    (SELECT min(ST_SRID(geom)) FROM t_building_3d) AS srid;
