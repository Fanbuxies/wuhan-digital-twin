package com.wuhan.twin.alarm.dto;

import java.io.Serializable;
import java.time.OffsetDateTime;

import lombok.Data;

/**
 * 新增告警的入参，模拟器与服务层之间的传输对象
 *
 * @author lvfan
 */
@Data
public class AlarmCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 监测对象主键，语义由 objectType 决定
     */
    private Long deviceId;

    /**
     * 监测对象类型：DEVICE 楼内设备 / FACILITY 市政设施
     */
    private String objectType;

    /**
     * 告警类型
     */
    private String alarmType;

    /**
     * 告警级别：1 预警 2 告警
     */
    private Integer alarmLevel;

    /**
     * 触发时的指标快照 JSON 文本
     */
    private String alarmValueJson;

    /**
     * 发生时间
     */
    private OffsetDateTime occurTime;
}
