package com.wuhan.twin.facility.vo;

import java.io.Serializable;
import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 市政设施分页视图对象，列表专用，不携带大字段
 *
 * @author lvfan
 */
@Data
@Schema(description = "市政设施分页记录")
public class FacilityPageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "设施编号")
    private String facilityCode;

    @Schema(description = "设施名称")
    private String facilityName;

    @Schema(description = "设施类型：CHARGING_PILE / STREET_LAMP / MANHOLE / BUS_STOP")
    private String facilityType;

    @Schema(description = "设施类型中文名")
    private String facilityTypeLabel;

    @Schema(description = "相对地面高度，单位米")
    private BigDecimal altitude;

    @Schema(description = "运行状态：ONLINE / OFFLINE / FAULT")
    private String status;

    @Schema(description = "点位经度")
    private Double lon;

    @Schema(description = "点位纬度")
    private Double lat;
}
