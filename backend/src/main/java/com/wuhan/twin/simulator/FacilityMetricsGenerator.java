package com.wuhan.twin.simulator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.wuhan.twin.alarm.enums.AlarmTypeEnum;
import com.wuhan.twin.facility.enums.FacilityTypeEnum;

/**
 * 按市政设施类型生成模拟指标与告警判定
 *
 * <p>与 MetricsGenerator 分开：两者的类型枚举、指标键与值域完全不相交，
 * 合成一个 switch 只会让分支数翻倍。返回值仍复用 MetricsGenerator.Sample，
 * 使模拟任务侧的落库与广播代码保持同一形状。</p>
 *
 * @author lvfan
 */
public final class FacilityMetricsGenerator {

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
     * 充电功率正常区间，单位千瓦
     */
    private static final double POWER_MIN = 0D;

    private static final double POWER_MAX = 60D;

    /**
     * 充电桩机身温度正常区间，单位摄氏度
     */
    private static final double PILE_TEMPERATURE_MIN = 20D;

    private static final double PILE_TEMPERATURE_MAX = 45D;

    /**
     * 充电桩机身温度告警区间
     */
    private static final double PILE_TEMPERATURE_ALARM_MIN = 70D;

    private static final double PILE_TEMPERATURE_ALARM_MAX = 95D;

    /**
     * 插枪状态：0 空闲 1 已插枪
     */
    private static final int PLUG_IDLE = 0;

    private static final int PLUG_CONNECTED = 1;

    /**
     * 插枪概率，约六成时段处于充电中
     */
    private static final double PLUG_PROBABILITY = 0.6D;

    /**
     * 路灯亮度正常区间，单位百分比
     */
    private static final double BRIGHTNESS_MIN = 60D;

    private static final double BRIGHTNESS_MAX = 100D;

    /**
     * 路灯不亮时的亮度
     */
    private static final double BRIGHTNESS_OFF = 0D;

    /**
     * 路灯单周期耗电量正常区间，单位千瓦时
     */
    private static final double ENERGY_MIN = 0.05D;

    private static final double ENERGY_MAX = 0.3D;

    /**
     * 耗电量保留小数位，量级比其他指标小两个数量级
     */
    private static final int ENERGY_SCALE = 3;

    /**
     * 井盖倾角正常区间，单位度
     */
    private static final double TILT_MIN = 0D;

    private static final double TILT_MAX = 3D;

    /**
     * 井盖倾角告警区间
     */
    private static final double TILT_ALARM_MIN = 15D;

    private static final double TILT_ALARM_MAX = 40D;

    /**
     * 井内水位正常区间，单位厘米
     */
    private static final double WATER_LEVEL_MIN = 0D;

    private static final double WATER_LEVEL_MAX = 20D;

    /**
     * 公交站客流正常区间，单位人每小时
     */
    private static final double PASSENGER_FLOW_MIN = 0D;

    private static final double PASSENGER_FLOW_MAX = 40D;

    /**
     * 电子站牌状态：0 灭屏 1 正常显示
     */
    private static final int SCREEN_OFF = 0;

    private static final int SCREEN_ON = 1;

    /**
     * 指标 JSON 字段名，与前端展示契约保持一致
     */
    private static final String KEY_POWER = "power";

    private static final String KEY_PLUGGED = "plugged";

    private static final String KEY_TEMPERATURE = "temperature";

    private static final String KEY_BRIGHTNESS = "brightness";

    private static final String KEY_ENERGY = "energy";

    private static final String KEY_TILT = "tilt";

    private static final String KEY_WATER_LEVEL = "waterLevel";

    private static final String KEY_PASSENGER_FLOW = "passengerFlow";

    private static final String KEY_SCREEN_ON = "screenOn";

    private FacilityMetricsGenerator() {
    }

    /**
     * 生成一次采样
     *
     * @param facilityType 设施类型
     * @param triggerAlarm 是否按告警形态生成
     * @return 采样结果
     */
    public static MetricsGenerator.Sample generate(FacilityTypeEnum facilityType, boolean triggerAlarm) {
        switch (facilityType) {
            case CHARGING_PILE:
                return chargingPile(triggerAlarm);
            case STREET_LAMP:
                return streetLamp(triggerAlarm);
            case MANHOLE:
                return manhole(triggerAlarm);
            case BUS_STOP:
                return busStop(triggerAlarm);
            default:
                // 枚举已穷举，此分支仅为编译期完整性兜底
                return MetricsGenerator.Sample.of(new LinkedHashMap<>(), LEVEL_NORMAL, null);
        }
    }

    private static MetricsGenerator.Sample chargingPile(boolean triggerAlarm) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        boolean plugged = ThreadLocalRandom.current().nextDouble() < PLUG_PROBABILITY;
        metrics.put(KEY_POWER, plugged ? randomDecimal(POWER_MIN, POWER_MAX) : BigDecimal.ZERO);
        metrics.put(KEY_PLUGGED, plugged ? PLUG_CONNECTED : PLUG_IDLE);
        metrics.put(KEY_TEMPERATURE, triggerAlarm
                ? randomDecimal(PILE_TEMPERATURE_ALARM_MIN, PILE_TEMPERATURE_ALARM_MAX)
                : randomDecimal(PILE_TEMPERATURE_MIN, PILE_TEMPERATURE_MAX));
        return triggerAlarm
                ? MetricsGenerator.Sample.of(metrics, LEVEL_ALARM, AlarmTypeEnum.CHARGE_FAULT.name())
                : MetricsGenerator.Sample.of(metrics, LEVEL_NORMAL, null);
    }

    private static MetricsGenerator.Sample streetLamp(boolean triggerAlarm) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put(KEY_BRIGHTNESS, triggerAlarm
                ? decimal(BRIGHTNESS_OFF, METRIC_SCALE)
                : randomDecimal(BRIGHTNESS_MIN, BRIGHTNESS_MAX));
        metrics.put(KEY_ENERGY, randomDecimal(ENERGY_MIN, ENERGY_MAX, ENERGY_SCALE));
        return triggerAlarm
                ? MetricsGenerator.Sample.of(metrics, LEVEL_WARN, AlarmTypeEnum.LAMP_OFF.name())
                : MetricsGenerator.Sample.of(metrics, LEVEL_NORMAL, null);
    }

    private static MetricsGenerator.Sample manhole(boolean triggerAlarm) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put(KEY_TILT, triggerAlarm
                ? randomDecimal(TILT_ALARM_MIN, TILT_ALARM_MAX)
                : randomDecimal(TILT_MIN, TILT_MAX));
        metrics.put(KEY_WATER_LEVEL, randomDecimal(WATER_LEVEL_MIN, WATER_LEVEL_MAX));
        return triggerAlarm
                ? MetricsGenerator.Sample.of(metrics, LEVEL_ALARM, AlarmTypeEnum.MANHOLE_TILT.name())
                : MetricsGenerator.Sample.of(metrics, LEVEL_NORMAL, null);
    }

    private static MetricsGenerator.Sample busStop(boolean triggerAlarm) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put(KEY_PASSENGER_FLOW, randomDecimal(PASSENGER_FLOW_MIN, PASSENGER_FLOW_MAX));
        metrics.put(KEY_SCREEN_ON, triggerAlarm ? SCREEN_OFF : SCREEN_ON);
        return triggerAlarm
                ? MetricsGenerator.Sample.of(metrics, LEVEL_WARN, AlarmTypeEnum.BUS_STOP_OFFLINE.name())
                : MetricsGenerator.Sample.of(metrics, LEVEL_NORMAL, null);
    }

    /**
     * 生成区间内随机值，保留一位小数
     */
    private static BigDecimal randomDecimal(double min, double max) {
        return randomDecimal(min, max, METRIC_SCALE);
    }

    /**
     * 生成区间内随机值，按指定小数位取整
     */
    private static BigDecimal randomDecimal(double min, double max, int scale) {
        return decimal(ThreadLocalRandom.current().nextDouble(min, max), scale);
    }

    private static BigDecimal decimal(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }
}
