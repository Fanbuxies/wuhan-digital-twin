package com.wuhan.twin.building.vo;

import java.io.Serializable;
import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 建筑分页记录视图对象
 *
 * <p>列表不需要轮廓几何，只带中心点经纬度，避免 5303 条带 footprint 撑爆响应。</p>
 *
 * @author lvfan
 */
@Data
@Schema(description = "建筑分页记录")
public class BuildingPageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

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

    @Schema(description = "中心点经度")
    private Double lon;

    @Schema(description = "中心点纬度")
    private Double lat;
}
