package com.wuhan.twin.building.vo;

import java.io.Serializable;
import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 建筑详情视图对象
 *
 * @author lvfan
 */
@Data
@Schema(description = "建筑详情")
public class BuildingDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "OSM way id")
    private Long osmId;

    @Schema(description = "建筑名称，多数建筑无名")
    private String name;

    @Schema(description = "OSM building 标签值")
    private String buildingType;

    @Schema(description = "层数")
    private Integer levels;

    @Schema(description = "建筑高度，单位米")
    private BigDecimal height;

    @Schema(description = "高度来源：osm_height / osm_levels / default_by_type")
    private String heightSource;

    @Schema(description = "地面基准高程，单位米")
    private BigDecimal baseAltitude;

    @Schema(description = "中心点经度")
    private Double lon;

    @Schema(description = "中心点纬度")
    private Double lat;

    @Schema(description = "轮廓 GeoJSON 几何对象")
    private JsonNode footprint;
}
