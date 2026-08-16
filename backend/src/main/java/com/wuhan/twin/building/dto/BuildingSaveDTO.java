package com.wuhan.twin.building.dto;

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
 * 建筑新增/编辑入参，两个操作共用同一结构
 *
 * <p>footprint 与 center 几何不由前端提交，service 层以 lon/lat 为中心
 * 在 SQL 侧构造（约 20 米见方的矩形 footprint + 中心点）。</p>
 *
 * @author lvfan
 */
@Data
@Schema(description = "建筑新增/编辑入参")
public class BuildingSaveDTO {

    /** 经纬度整数位上限，对应地球坐标 180/90 */
    private static final int MAX_LON_INTEGER = 3;

    /** 经纬度小数位，约 0.1m 精度 */
    private static final int LON_LAT_FRACTION = 6;

    /** 层数下限 */
    private static final long MIN_LEVELS = 1L;

    /** 层数上限，远超现实高楼 */
    private static final long MAX_LEVELS = 500L;

    @Schema(description = "建筑名称，多数建筑无名")
    @Size(max = 128)
    private String name;

    @Schema(description = "建筑类型，OSM building 标签值，如 residential")
    @Size(max = 32)
    private String buildingType;

    @Schema(description = "层数")
    @Min(MIN_LEVELS)
    @Max(MAX_LEVELS)
    private Integer levels;

    @Schema(description = "建筑高度，单位米")
    @NotNull
    @Digits(integer = 4, fraction = 2)
    @DecimalMin("0.1")
    @DecimalMax("9999.99")
    private BigDecimal height;

    @Schema(description = "高度来源：osm_height / osm_levels / default_by_type")
    @NotBlank
    @Size(max = 24)
    private String heightSource;

    @Schema(description = "中心点经度，footprint 以该点为中心生成")
    @NotNull
    @Digits(integer = MAX_LON_INTEGER, fraction = LON_LAT_FRACTION)
    @DecimalMin("-180")
    @DecimalMax("180")
    private BigDecimal lon;

    @Schema(description = "中心点纬度")
    @NotNull
    @Digits(integer = MAX_LON_INTEGER, fraction = LON_LAT_FRACTION)
    @DecimalMin("-90")
    @DecimalMax("90")
    private BigDecimal lat;
}
