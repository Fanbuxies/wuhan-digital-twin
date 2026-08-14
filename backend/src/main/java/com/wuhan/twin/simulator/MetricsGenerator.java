package com.wuhan.twin.simulator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.wuhan.twin.device.enums.DeviceTypeEnum;
import lombok.Getter;

/**
 * 按设备类型生成模拟指标与告警判定
 *
 * <p>正常区间与告警值域全部常量化，便于统一调整。数值一律 BigDecimal，避免 double 落库精度问题。</p>
 *
 * @author lvfan
 */
public final class MetricsGenerator {

    /**
     * 正常级别
     */
    private static final int LEVEL_NORMAL = 0;

    /**
     * 预警级别
     */
    private static final int LEVEL_WARN = 1;

    /**
     * 告警级别
     */
    private static final int LEVEL_ALARM = 2;

    /**
     * 指标保留小数位
     */
    private static final int METRIC_SCALE = 1;

    /**
     * 烟雾浓度正常区间，单位 ppm
     */
    private static final double SMOKE_MIN = 0D;

    private static final double SMOKE_MAX = 30D;

    /**
     * 烟雾浓度告警区间
     */
    private static final double SMOKE_ALARM_MIN = 120D;

    private static final double SMOKE_ALARM_MAX = 300D;

    /**
     * 电池电量正常区间，单位百分比
     */
    private static final double BATTERY_MIN = 60D;

    private static final double BATTERY_MAX = 100D;

    /**
     * 湿度正常区间，单位百分比
     */
    private static final double HUMIDITY_MIN = 40D;

    private static final double HUMIDITY_MAX = 70D;

    /**
     * 水浸状态：0 无水 1 进水
     */
    private static final int LEAK_NONE = 0;

    private static final int LEAK_DETECTED = 1;

    /**
     * 温度正常区间，单位摄氏度
     */
    private static final double TEMPERATURE_MIN = 18D;

    private static final double TEMPERATURE_MAX = 28D;

    /**
     * 温度预警区间
     */
    private static final double TEMPERATURE_WARN_MIN = 38D;

    private static final double TEMPERATURE_WARN_MAX = 50D;

    /**
     * 电压正常区间，单位伏
     */
    private static final double VOLTAGE_MIN = 215D;

    private static final double VOLTAGE_MAX = 235D;

    /**
     * 电流正常区间，单位安
     */
    private static final double CURRENT_MIN = 1D;

    private static final double CURRENT_MAX = 15D;

    /**
     * 电流告警区间
     */
    private static final double CURRENT_ALARM_MIN = 28D;

    private static final double CURRENT_ALARM_MAX = 45D;

    /**
     * 码率正常区间，单位 kbps
     */
    private static final double BITRATE_MIN = 2000D;

    private static final double BITRATE_MAX = 4000D;

    /**
     * 码率异常区间，视为断流
     */
    private static final double BITRATE_LOST_MIN = 0D;

    private static final double BITRATE_LOST_MAX = 200D;

    /**
     * 摄像头固定帧率
     */
    private static final int FRAME_RATE = 25;

    private MetricsGenerator() {
    }

    /**
     * 生成一次采样
     *
     * @param deviceType 设备类型
     * @param triggerAlarm 是否按告警形态生成
     * @return 采样结果
     */
    public static Sample generate(DeviceTypeEnum deviceType, boolean triggerAlarm) {
        switch (deviceType) {
            case SMOKE:
                return smoke(triggerAlarm);
            case WATER:
                return water(triggerAlarm);
            case TEMP_HUMI:
                return tempHumi(triggerAlarm);
            case ELECTRIC:
                return electric(triggerAlarm);
            case CAMERA:
                return camera(triggerAlarm);
            default:
                // 枚举已穷举，此分支仅为编译期完整性兜底
                return new Sample(new LinkedHashMap<>(), LEVEL_NORMAL, null);
        }
    }

    private static Sample smoke(boolean triggerAlarm) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("smoke", triggerAlarm
                ? randomDecimal(SMOKE_ALARM_MIN, SMOKE_ALARM_MAX)
                : randomDecimal(SMOKE_MIN, SMOKE_MAX));
        metrics.put("battery", randomDecimal(BATTERY_MIN, BATTERY_MAX));
        return triggerAlarm
                ? new Sample(metrics, LEVEL_ALARM, "SMOKE_ALARM")
                : new Sample(metrics, LEVEL_NORMAL, null);
    }

    private static Sample water(boolean triggerAlarm) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("leak", triggerAlarm ? LEAK_DETECTED : LEAK_NONE);
        metrics.put("humidity", randomDecimal(HUMIDITY_MIN, HUMIDITY_MAX));
        return triggerAlarm
                ? new Sample(metrics, LEVEL_ALARM, "WATER_LEAK")
                : new Sample(metrics, LEVEL_NORMAL, null);
    }

    private static Sample tempHumi(boolean triggerAlarm) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("temperature", triggerAlarm
                ? randomDecimal(TEMPERATURE_WARN_MIN, TEMPERATURE_WARN_MAX)
                : randomDecimal(TEMPERATURE_MIN, TEMPERATURE_MAX));
        metrics.put("humidity", randomDecimal(HUMIDITY_MIN, HUMIDITY_MAX));
        return triggerAlarm
                ? new Sample(metrics, LEVEL_WARN, "TEMP_HIGH")
                : new Sample(metrics, LEVEL_NORMAL, null);
    }

    private static Sample electric(boolean triggerAlarm) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("voltage", randomDecimal(VOLTAGE_MIN, VOLTAGE_MAX));
        metrics.put("current", triggerAlarm
                ? randomDecimal(CURRENT_ALARM_MIN, CURRENT_ALARM_MAX)
                : randomDecimal(CURRENT_MIN, CURRENT_MAX));
        return triggerAlarm
                ? new Sample(metrics, LEVEL_ALARM, "CURRENT_HIGH")
                : new Sample(metrics, LEVEL_NORMAL, null);
    }

    private static Sample camera(boolean triggerAlarm) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("bitrate", triggerAlarm
                ? randomDecimal(BITRATE_LOST_MIN, BITRATE_LOST_MAX)
                : randomDecimal(BITRATE_MIN, BITRATE_MAX));
        metrics.put("frameRate", FRAME_RATE);
        return triggerAlarm
                ? new Sample(metrics, LEVEL_WARN, "STREAM_LOST")
                : new Sample(metrics, LEVEL_NORMAL, null);
    }

    /**
     * 生成区间内随机值，保留一位小数
     */
    private static BigDecimal randomDecimal(double min, double max) {
        double value = ThreadLocalRandom.current().nextDouble(min, max);
        return BigDecimal.valueOf(value).setScale(METRIC_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 一次采样的结果
     */
    @Getter
    public static final class Sample {

        /**
         * 指标键值对
         */
        private final Map<String, Object> metrics;

        /**
         * 告警级别：0 正常 1 预警 2 告警
         */
        private final Integer alarmLevel;

        /**
         * 告警类型，正常时为 null
         */
        private final String alarmType;

        private Sample(Map<String, Object> metrics, Integer alarmLevel, String alarmType) {
            this.metrics = metrics;
            this.alarmLevel = alarmLevel;
            this.alarmType = alarmType;
        }

        /**
         * 本次采样是否命中告警
         *
         * @return 命中返回 true
         */
        public boolean alarmed() {
            return alarmType != null;
        }
    }
}
