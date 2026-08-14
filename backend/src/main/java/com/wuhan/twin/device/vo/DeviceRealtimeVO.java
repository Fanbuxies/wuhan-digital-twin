package com.wuhan.twin.device.vo;

import java.io.Serializable;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 设备实时状态视图对象，同时作为 WebSocket DEVICE_UPDATE 的推送体元素
 *
 * @author lvfan
 */
@Data
@Schema(description = "设备实时状态")
public class DeviceRealtimeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "设备主键")
    private Long deviceId;

    @Schema(description = "指标键值对，字段随设备类型而异")
    private JsonNode metrics;

    @Schema(description = "告警级别：0 正常 1 预警 2 告警")
    private Integer alarmLevel;

    @Schema(description = "采样时刻")
    private OffsetDateTime ts;
}
