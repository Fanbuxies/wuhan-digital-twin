package com.wuhan.twin.device.dto;

import java.io.Serializable;
import java.time.OffsetDateTime;

import lombok.Data;

/**
 * 单设备一次采样结果，模拟器与服务层之间的传输对象
 *
 * @author lvfan
 */
@Data
public class DeviceMetricsDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 设备主键
     */
    private Long deviceId;

    /**
     * 指标 JSON 文本
     */
    private String metricsJson;

    /**
     * 告警级别：0 正常 1 预警 2 告警
     */
    private Integer alarmLevel;

    /**
     * 采样时刻
     */
    private OffsetDateTime ts;
}
