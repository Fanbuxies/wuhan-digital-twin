package com.wuhan.twin.device.mapper;

import java.util.List;

import com.wuhan.twin.device.dto.DeviceMetricsDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 历史遥测 mapper。表按 ts 范围分区，写入只走默认分区兜底
 *
 * @author lvfan
 */
@Mapper
public interface DeviceTelemetryMapper {

    /**
     * 批量追加历史遥测
     *
     * @param list 采样结果，不可为空集合
     * @return 影响行数
     */
    int batchInsert(@Param("list") List<DeviceMetricsDTO> list);

    /**
     * 按监测对象删除历史遥测，删除设备台账前清理关联数据
     *
     * @param deviceId   监测对象主键
     * @param objectType 监测对象类型：DEVICE / FACILITY
     * @return 影响行数
     */
    int deleteByDeviceId(@Param("deviceId") Long deviceId,
                         @Param("objectType") String objectType);
}
