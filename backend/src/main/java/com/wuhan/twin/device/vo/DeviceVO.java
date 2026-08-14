package com.wuhan.twin.device.vo;

import java.io.Serializable;
import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 设备视图对象
 *
 * @author lvfan
 */
@Data
@Schema(description = "设备信息")
public class DeviceVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "设备编号")
    private String deviceCode;

    @Schema(description = "设备名称")
    private String deviceName;

    @Schema(description = "设备类型：SMOKE / WATER / TEMP_HUMI / ELECTRIC / CAMERA")
    private String deviceType;

    @Schema(description = "设备类型中文名")
    private String deviceTypeLabel;

    @Schema(description = "所属建筑主键")
    private Long buildingId;

    @Schema(description = "所在楼层")
    private Integer floor;

    @Schema(description = "安装高度，单位米")
    private BigDecimal altitude;

    @Schema(description = "运行状态：ONLINE / OFFLINE / FAULT")
    private String status;

    @Schema(description = "点位经度")
    private Double lon;

    @Schema(description = "点位纬度")
    private Double lat;
}
