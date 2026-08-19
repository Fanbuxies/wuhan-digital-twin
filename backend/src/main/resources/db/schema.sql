CREATE TABLE IF NOT EXISTS t_building (
    id bigserial PRIMARY KEY,
    osm_id bigint UNIQUE,
    name varchar(128),
    building_type varchar(32),
    levels int,
    height numeric(6,2) NOT NULL,
    height_source varchar(24) NOT NULL CHECK (height_source IN ('osm_height', 'osm_levels', 'default_by_type')),
    base_altitude numeric(6,2) DEFAULT 0,
    footprint geometry(Polygon, 4326) NOT NULL,
    center geometry(Point, 4326),
    created_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_t_building_footprint ON t_building USING GIST (footprint);

CREATE INDEX IF NOT EXISTS idx_t_building_center ON t_building USING GIST (center);

-- 设备台账。building_id 不建外键，引用完整性由 service 层保证
CREATE TABLE IF NOT EXISTS t_device (
    id bigserial PRIMARY KEY,
    device_code varchar(64) NOT NULL UNIQUE,
    device_name varchar(128),
    device_type varchar(32) NOT NULL CHECK (device_type IN ('SMOKE', 'WATER', 'TEMP_HUMI', 'ELECTRIC', 'CAMERA')),
    building_id bigint NOT NULL,
    floor int,
    location geometry(Point, 4326),
    altitude numeric(6,2),
    status varchar(16) NOT NULL DEFAULT 'ONLINE' CHECK (status IN ('ONLINE', 'OFFLINE', 'FAULT')),
    install_time timestamptz,
    created_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_t_device_building_id ON t_device (building_id);

CREATE INDEX IF NOT EXISTS idx_t_device_location ON t_device USING GIST (location);

CREATE INDEX IF NOT EXISTS idx_t_device_type_status ON t_device (device_type, status);

-- 实时状态，一个监测对象一行，由模拟器 UPSERT。
-- device_id 为监测对象主键，语义由 object_type 决定：DEVICE 指向 t_device.id，FACILITY 指向 t_facility.id。
-- 两张台账表的 id 都是从 1 开始的 bigserial，故主键必须带 object_type 才不撞键
CREATE TABLE IF NOT EXISTS t_device_realtime (
    device_id bigint NOT NULL,
    object_type varchar(16) NOT NULL DEFAULT 'DEVICE' CHECK (object_type IN ('DEVICE', 'FACILITY')),
    metrics jsonb NOT NULL,
    alarm_level smallint NOT NULL DEFAULT 0 CHECK (alarm_level IN (0, 1, 2)),
    update_time timestamptz DEFAULT now(),
    PRIMARY KEY (object_type, device_id)
);

CREATE INDEX IF NOT EXISTS idx_t_device_realtime_alarm_level ON t_device_realtime (alarm_level);

-- 历史遥测，按 ts 范围分区。分区表主键必须包含分区键，故用 (id, ts) 复合主键
CREATE TABLE IF NOT EXISTS t_device_telemetry (
    id bigserial,
    device_id bigint NOT NULL,
    object_type varchar(16) NOT NULL DEFAULT 'DEVICE' CHECK (object_type IN ('DEVICE', 'FACILITY')),
    metrics jsonb,
    ts timestamptz NOT NULL,
    PRIMARY KEY (id, ts)
) PARTITION BY RANGE (ts);

-- 默认分区兜住所有未显式建分区的时间段，避免写入报错
CREATE TABLE IF NOT EXISTS t_device_telemetry_default PARTITION OF t_device_telemetry DEFAULT;

CREATE INDEX IF NOT EXISTS idx_t_device_telemetry_device_ts ON t_device_telemetry (device_id, ts DESC);

-- 告警记录
CREATE TABLE IF NOT EXISTS t_alarm (
    id bigserial PRIMARY KEY,
    device_id bigint NOT NULL,
    object_type varchar(16) NOT NULL DEFAULT 'DEVICE' CHECK (object_type IN ('DEVICE', 'FACILITY')),
    alarm_type varchar(32) NOT NULL,
    alarm_level smallint NOT NULL DEFAULT 0 CHECK (alarm_level IN (0, 1, 2)),
    alarm_value jsonb,
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CONFIRMED', 'CLOSED')),
    occur_time timestamptz NOT NULL DEFAULT now(),
    close_time timestamptz,
    created_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_t_alarm_status_occur_time ON t_alarm (status, occur_time DESC);

CREATE INDEX IF NOT EXISTS idx_t_alarm_device_id ON t_alarm (device_id);

-- 道路中心线，仅用于沿路插值生成路灯/井盖点位，不对外提供接口
CREATE TABLE IF NOT EXISTS t_road (
    id bigserial PRIMARY KEY,
    osm_id bigint NOT NULL UNIQUE,
    name varchar(128),
    road_type varchar(32) NOT NULL,
    geom geometry(LineString, 4326) NOT NULL,
    length_m numeric(10,2) NOT NULL,
    created_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_t_road_geom ON t_road USING GIST (geom);

-- 室外市政设施台账。与 t_device 分表：设施不属于任何建筑，无楼层概念，
-- 点位在街面而非楼内，故不复用 t_device 的 building_id NOT NULL 约束
CREATE TABLE IF NOT EXISTS t_facility (
    id bigserial PRIMARY KEY,
    facility_code varchar(64) NOT NULL UNIQUE,
    facility_name varchar(128),
    facility_type varchar(32) NOT NULL
        CHECK (facility_type IN ('CHARGING_PILE', 'STREET_LAMP', 'MANHOLE', 'BUS_STOP')),
    -- OSM 直取的点有值，沿路插值生成的为 null
    osm_id bigint,
    -- 插值生成的点记录所属道路，OSM 直取的为 null。不建外键，引用完整性由数据脚本保证
    road_id bigint,
    location geometry(Point, 4326) NOT NULL,
    altitude numeric(6,2) DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'ONLINE' CHECK (status IN ('ONLINE', 'OFFLINE', 'FAULT')),
    source varchar(16) NOT NULL CHECK (source IN ('osm', 'road_interp')),
    install_time timestamptz,
    created_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_t_facility_location ON t_facility USING GIST (location);

CREATE INDEX IF NOT EXISTS idx_t_facility_type_status ON t_facility (facility_type, status);

-- 中心城区行政区边界，供建筑入库时按 ST_Intersects 裁剪范围（见 data-prep/load_to_pg.py）
CREATE TABLE IF NOT EXISTS t_district (
    id bigserial PRIMARY KEY,
    osm_id bigint NOT NULL UNIQUE,
    name varchar(64) NOT NULL,
    boundary geometry(MultiPolygon, 4326) NOT NULL,
    created_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_t_district_boundary ON t_district USING GIST (boundary);
