package com.wuhan.twin.device.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 设备数据对象，对应 t_device
 *
 * <p>location 为 PostGIS 几何列，不映射为 Java 属性；
 * 经纬度由 SQL 侧 ST_X、ST_Y 转换后填入派生字段。</p>
 *
 * @author lvfan
 */
@Data
@TableName("t_device")
public class DeviceDO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 设备编号，业务唯一
     */
    private String deviceCode;

    /**
     * 设备名称
     */
    private String deviceName;

    /**
     * 设备类型：SMOKE / WATER / TEMP_HUMI / ELECTRIC / CAMERA
     */
    private String deviceType;

    /**
     * 所属建筑主键
     */
    private Long buildingId;

    /**
     * 所在楼层
     */
    private Integer floor;

    /**
     * 安装高度，单位米
     */
    private BigDecimal altitude;

    /**
     * 运行状态：ONLINE / OFFLINE / FAULT
     */
    private String status;

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
