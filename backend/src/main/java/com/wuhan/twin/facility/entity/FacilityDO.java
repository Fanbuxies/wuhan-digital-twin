package com.wuhan.twin.facility.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 市政设施数据对象，对应 t_facility
 *
 * <p>location 为 PostGIS 几何列，不映射为 Java 属性；
 * 经纬度由 SQL 侧 ST_X、ST_Y 转换后填入派生字段。</p>
 *
 * @author lvfan
 */
@Data
@TableName("t_facility")
public class FacilityDO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 设施编号，业务唯一
     */
    private String facilityCode;

    /**
     * 设施名称
     */
    private String facilityName;

    /**
     * 设施类型：CHARGING_PILE / STREET_LAMP / MANHOLE / BUS_STOP
     */
    private String facilityType;

    /**
     * OSM 要素主键，沿路插值生成的点为 null
     */
    private Long osmId;

    /**
     * 所属道路主键，OSM 直取的点为 null
     */
    private Long roadId;

    /**
     * 相对地面高度，单位米
     */
    private BigDecimal altitude;

    /**
     * 运行状态：ONLINE / OFFLINE / FAULT
     */
    private String status;

    /**
     * 点位来源：osm 直取 / road_interp 沿路插值
     */
    private String source;

    /**
     * 安装时间
     */
    private OffsetDateTime installTime;

    /**
     * 入库时间
     */
    private OffsetDateTime createdAt;

    /**
     * 点位经度，由 ST_X 生成
     */
    @TableField(exist = false)
    private Double lon;

    /**
     * 点位纬度，由 ST_Y 生成
     */
    @TableField(exist = false)
    private Double lat;
}
