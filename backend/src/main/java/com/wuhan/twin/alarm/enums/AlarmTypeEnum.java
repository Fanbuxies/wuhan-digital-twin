package com.wuhan.twin.alarm.enums;

import lombok.Getter;

/**
 * 告警类型，取值与 t_alarm.alarm_type 的取值约定保持一致
 *
 * @author lvfan
 */
@Getter
public enum AlarmTypeEnum {

    /**
     * 烟感报警
     */
    SMOKE_ALARM("烟感报警"),

    /**
     * 水浸报警
     */
    WATER_LEAK("水浸报警"),

    /**
     * 温度过高预警
     */
    TEMP_HIGH("温度过高预警"),

    /**
     * 电流过高报警
     */
    CURRENT_HIGH("电流过高报警"),

    /**
     * 视频断流预警
     */
    STREAM_LOST("视频断流预警"),

    /**
     * 充电桩故障
     */
    CHARGE_FAULT("充电桩故障"),

    /**
     * 路灯不亮
     */
    LAMP_OFF("路灯不亮"),

    /**
     * 井盖倾斜
     */
    MANHOLE_TILT("井盖倾斜"),

    /**
     * 站牌离线
     */
    BUS_STOP_OFFLINE("站牌离线");

    /**
     * 中文名称
     */
    private final String label;

    AlarmTypeEnum(String label) {
        this.label = label;
    }
}
