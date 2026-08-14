package com.wuhan.twin.device.entity;

import java.io.Serializable;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 设备实时状态数据对象，对应 t_device_realtime
 *
 * <p>metrics 为 jsonb 列，Java 侧以文本收发：写入用 ::jsonb 强转，读取用 metrics::text。</p>
 *
 * @author lvfan
 */
@Data
@TableName("t_device_realtime")
public class DeviceRealtimeDO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 设备主键，与 t_device.id 同值，由业务侧指定而非数据库生成
     */
    @TableId(value = "device_id", type = IdType.INPUT)
    private Long deviceId;

    /**
     * 指标 JSON 文本
     */
    @TableField("metrics")
    private String metricsJson;

    /**
     * 告警级别：0 正常 1 预警 2 告警
     */
    private Integer alarmLevel;

    /**
     * 最近更新时间
     */
    private OffsetDateTime updateTime;
}
