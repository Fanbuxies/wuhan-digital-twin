package com.wuhan.twin.alarm.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuhan.twin.alarm.dto.AlarmCreateDTO;
import com.wuhan.twin.alarm.entity.AlarmDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 告警 mapper
 *
 * @author lvfan
 */
@Mapper
public interface AlarmMapper extends BaseMapper<AlarmDO> {

    /**
     * 批量新增告警，状态固定为 PENDING
     *
     * @param list 告警入参，不可为空集合
     * @return 影响行数
     */
    int batchInsert(@Param("list") List<AlarmCreateDTO> list);

    /**
     * 查询存在待处理告警的监测对象主键，供模拟器去重，避免同一对象反复刷告警
     *
     * @param objectType 监测对象类型：DEVICE / FACILITY
     * @return 监测对象主键列表，无数据返回空集合
     */
    List<Long> selectPendingDeviceIds(@Param("objectType") String objectType);

    /**
     * 按监测对象删除告警记录，删除设备台账前清理关联数据
     *
     * @param deviceId   监测对象主键
     * @param objectType 监测对象类型：DEVICE / FACILITY
     * @return 影响行数
     */
    int deleteByDeviceId(@Param("deviceId") Long deviceId,
                         @Param("objectType") String objectType);
}
