package com.wuhan.twin.alarm.service;

import java.util.List;
import java.util.Set;

import com.wuhan.twin.alarm.dto.AlarmCreateDTO;
import com.wuhan.twin.alarm.vo.AlarmVO;

/**
 * 告警服务
 *
 * @author lvfan
 */
public interface AlarmService {

    /**
     * 批量新增告警，状态固定为 PENDING
     *
     * @param list 告警入参，空集合直接返回
     */
    void createAlarms(List<AlarmCreateDTO> list);

    /**
     * 查询存在待处理告警的设备主键集合，供模拟器去重
     *
     * @return 无数据返回空集合
     */
    Set<Long> listPendingDeviceIds();

    /**
     * 把新增入参转成推送体
     *
     * @param list 告警入参
     * @return 推送体列表，入参为空时返回空集合
     */
    List<AlarmVO> toAlarmVoList(List<AlarmCreateDTO> list);
}
