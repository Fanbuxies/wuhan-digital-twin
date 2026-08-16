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
     * 批量写入实时状态，已存在的监测对象覆盖指标与告警级别
     *
     * @param list 采样结果，不可为空集合，每项须带 objectType
     * @return 影响行数
     */
    int batchUpsert(@Param("list") List<DeviceMetricsDTO> list);

    /**
     * 查询单个监测对象的实时状态
     *
     * @param deviceId   监测对象主键
     * @param objectType 监测对象类型：DEVICE / FACILITY
     * @return 无实时数据时返回 null
     */
    DeviceRealtimeDO selectByDeviceId(@Param("deviceId") Long deviceId,
                                      @Param("objectType") String objectType);

    /**
     * 按监测对象删除实时状态，删除设备台账前清理关联数据
     *
     * @param deviceId   监测对象主键
     * @param objectType 监测对象类型：DEVICE / FACILITY
     * @return 影响行数
     */
    int deleteByDeviceId(@Param("deviceId") Long deviceId,
                         @Param("objectType") String objectType);
}
