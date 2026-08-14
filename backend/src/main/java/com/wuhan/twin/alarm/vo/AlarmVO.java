package com.wuhan.twin.alarm.vo;

import java.io.Serializable;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 告警视图对象，同时作为 WebSocket ALARM_NEW 的推送体
 *
 * @author lvfan
 */
@Data
@Schema(description = "告警信息")
public class AlarmVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "设备主键")
    private Long deviceId;

    @Schema(description = "告警类型")
    private String alarmType;

    @Schema(description = "告警级别：1 预警 2 告警")
    private Integer alarmLevel;

    @Schema(description = "触发时的指标快照")
    private JsonNode alarmValue;

    @Schema(description = "处理状态：PENDING / CONFIRMED / CLOSED")
    private String status;

    @Schema(description = "发生时间")
    private OffsetDateTime occurTime;
}
