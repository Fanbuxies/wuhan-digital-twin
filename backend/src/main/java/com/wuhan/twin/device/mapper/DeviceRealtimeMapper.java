package com.wuhan.twin.device.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuhan.twin.device.dto.DeviceMetricsDTO;
import com.wuhan.twin.device.entity.DeviceRealtimeDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 设备实时状态 mapper
 *
 * @author lvfan
 */
@Mapper
public interface DeviceRealtimeMapper extends BaseMapper<DeviceRealtimeDO> {

    /**
     * 批量写入实时状态，已存在的设备覆盖指标与告警级别
     *
     * @param list 采样结果，不可为空集合
     * @return 影响行数
     */
    int batchUpsert(@Param("list") List<DeviceMetricsDTO> list);

    /**
     * 查询单设备实时状态
     *
     * @param deviceId 设备主键
     * @return 无实时数据时返回 null
     */
    DeviceRealtimeDO selectByDeviceId(@Param("deviceId") Long deviceId);
}
