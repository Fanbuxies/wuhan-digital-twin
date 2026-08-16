package com.wuhan.twin.stat.vo;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 概览指标视图对象
 *
 * @author lvfan
 */
@Data
@Schema(description = "概览指标")
public class StatOverviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "设备总数")
    private Long deviceTotal;

    @Schema(description = "在线设备数")
    private Long onlineCount;

    @Schema(description = "设备待处理告警数")
    private Long alarmCount;

    @Schema(description = "市政设施总数")
    private Long facilityTotal;

    @Schema(description = "在线市政设施数")
    private Long facilityOnlineCount;

    @Schema(description = "市政设施待处理告警数")
    private Long facilityAlarmCount;
}
