package com.wuhan.twin.building.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 建筑数据对象，对应 t_building
 *
 * <p>footprint、center 为 PostGIS 几何列，不映射为 Java 属性；
 * 需要几何信息时由自定义 SQL 用 ST_AsGeoJSON、ST_X、ST_Y 转换后填入下方派生字段。</p>
 *
 * @author lvfan
 */
@Data
@TableName("t_building")
public class BuildingDO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * OSM way id
     */
    private Long osmId;

    /**
     * 建筑名称，多数建筑无名
     */
    private String name;

    /**
     * OSM building 标签值
     */
    private String buildingType;

    /**
     * 层数
     */
    private Integer levels;

    /**
     * 建筑高度，单位米
     */
    private BigDecimal height;

    /**
     * 高度来源：osm_height / osm_levels / default_by_type
     */
    private String heightSource;

    /**
     * 地面基准高程，单位米
     */
    private BigDecimal baseAltitude;

    /**
     * 入库时间
     */
    private OffsetDateTime createdAt;

    /**
     * 轮廓 GeoJSON 文本，由 ST_AsGeoJSON 生成
     */
    @TableField(exist = false)
    private String footprintGeoJson;

    /**
     * 中心点经度，由 ST_X 生成
     */
    @TableField(exist = false)
    private Double lon;

    /**
     * 中心点纬度，由 ST_Y 生成
     */
    @TableField(exist = false)
    private Double lat;
}
