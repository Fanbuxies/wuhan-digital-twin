package com.wuhan.twin.building.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 建筑分页查询参数
 *
 * @author lvfan
 */
@Data
@Schema(description = "建筑分页查询参数")
public class BuildingPageQuery {

    /** 页码下限 */
    private static final long MIN_CURRENT = 1L;

    /** 每页条数下限 */
    private static final long MIN_SIZE = 1L;

    /** 每页条数上限，与 MybatisPlusConfig 分页拦截器上限一致 */
    private static final long MAX_SIZE = 500L;

    /** 默认页码 */
    private static final long DEFAULT_CURRENT = 1L;

    /** 默认每页条数 */
    private static final long DEFAULT_SIZE = 20L;

    @Schema(description = "页码，从 1 开始")
    @Min(MIN_CURRENT)
    private Long current = DEFAULT_CURRENT;

    @Schema(description = "每页条数")
    @Min(MIN_SIZE)
    @Max(MAX_SIZE)
    private Long size = DEFAULT_SIZE;

    @Schema(description = "关键字，按建筑名称模糊匹配")
    private String keyword;

    @Schema(description = "建筑类型，OSM building 标签值，如 residential")
    private String buildingType;
}
