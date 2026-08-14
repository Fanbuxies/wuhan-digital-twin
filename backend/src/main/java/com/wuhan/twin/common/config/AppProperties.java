package com.wuhan.twin.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 业务可调参数，前缀 app
 *
 * @author lvfan
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /**
     * 3D Tiles 相关配置
     */
    private Tileset tileset = new Tileset();

    /**
     * 建筑接口相关配置
     */
    private Building building = new Building();

    /**
     * 设备数据模拟器相关配置
     */
    private Simulator simulator = new Simulator();

    /**
     * 3D Tiles 数据源与初始视角
     */
    @Data
    public static class Tileset {

        /**
         * tileset.json 地址。未生成 3D Tiles 时留空，接口返回 null，前端走 GeoJSON 降级
         */
        private String url;

        /**
         * 初始视角
         */
        private Camera camera = new Camera();
    }

    /**
     * 相机初始参数
     */
    @Data
    public static class Camera {

        /**
         * 经度
         */
        private Double lon;

        /**
         * 纬度
         */
        private Double lat;

        /**
         * 视高，单位米
         */
        private Double height;

        /**
         * 方位角，单位度
         */
        private Double heading;

        /**
         * 俯仰角，单位度，负值表示俯视
         */
        private Double pitch;
    }

    /**
     * 建筑接口配置
     */
    @Data
    public static class Building {

        /**
         * GeoJSON 单次返回要素上限，超出则截断
         */
        private Integer geojsonMaxFeatures = 3000;
    }

    /**
     * 设备数据模拟器配置
     */
    @Data
    public static class Simulator {

        /**
         * 模拟器总开关，关闭后不注册调度线程
         */
        private Boolean enabled = Boolean.TRUE;

        /**
         * 调度周期，单位毫秒
         */
        private Long fixedRate = 3000L;

        /**
         * 单设备单次调度的告警触发概率。250 台设备按 5% 算每分钟上百条，故默认压到 0.002
         */
        private Double alarmProbability = 0.002D;

        /**
         * 每多少次调度落一次历史遥测。3 秒一次调度，5 即 15 秒一批，避免历史表暴涨
         */
        private Integer telemetryTickInterval = 5;
    }
}
