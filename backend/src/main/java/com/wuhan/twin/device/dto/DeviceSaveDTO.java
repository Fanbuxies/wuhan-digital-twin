package com.wuhan.twin.device.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 设备新增/编辑入参，两个操作共用同一结构
 *
 * <p>location 几何不由前端提交，service 层以 lon/lat 在 SQL 侧构造；
 * deviceType 与 status 的枚举取值由 service 层校验。</p>
 *
 * @author lvfan
 */
@Data
@Schema(description = "设备新增/编辑入参")
public class DeviceSaveDTO {

    /** 经纬度整数位上限，对应地球坐标 180/90 */
    private static final int MAX_LON_INTEGER = 3;

    /** 经纬度小数位，约 0.1m 精度 */
    private static final int LON_LAT_FRACTION = 6;

    /** 楼层下限，地下层按负楼层表示 */
    private static final long MIN_FLOOR = -50L;

    /** 楼层上限 */
    private static final long MAX_FLOOR = 500L;

    @Schema(description = "设备编号，全局唯一")
    @NotBlank
    @Size(max = 64)
    private String deviceCode;

    @Schema(description = "设备名称")
    @Size(max = 128)
    private String deviceName;

    @Schema(description = "设备类型：SMOKE / WATER / TEMP_HUMI / ELECTRIC / CAMERA")
    @NotBlank
    @Size(max = 32)
    private String deviceType;

    @Schema(description = "所属建筑主键，建筑必须存在")
    @NotNull
    @Min(1)
    private Long buildingId;

    @Schema(description = "所在楼层")
    @Min(MIN_FLOOR)
    @Max(MAX_FLOOR)
    private Integer floor;

    @Schema(description = "安装高度，单位米")
    @Digits(integer = 4, fraction = 2)
    @DecimalMin("0")
    @DecimalMax("9999.99")
    private BigDecimal altitude;

    @Schema(description = "运行状态：ONLINE / OFFLINE / FAULT")
    @NotBlank
    @Size(max = 16)
    private String status;

    @Schema(description = "点位经度")
    @NotNull
    @Digits(integer = MAX_LON_INTEGER, fraction = LON_LAT_FRACTION)
    @DecimalMin("-180")
    @DecimalMax("180")
    private BigDecimal lon;

    @Schema(description = "点位纬度")
    @NotNull
    @Digits(integer = MAX_LON_INTEGER, fraction = LON_LAT_FRACTION)
    @DecimalMin("-90")
    @DecimalMax("90")
    private BigDecimal lat;
}
