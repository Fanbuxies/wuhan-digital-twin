package com.wuhan.twin.alarm.service;

import java.util.List;
import java.util.Set;

import com.wuhan.twin.alarm.dto.AlarmCreateDTO;
import com.wuhan.twin.alarm.vo.AlarmVO;
import com.wuhan.twin.common.enums.ObjectTypeEnum;

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
     * 查询存在待处理告警的监测对象主键集合，供模拟器去重
     *
     * @param objectType 监测对象类型，设备与设施共用同一张告警表，须显式区分
     * @return 无数据返回空集合
     */
    Set<Long> listPendingDeviceIds(ObjectTypeEnum objectType);

    /**
     * 把新增入参转成推送体
     *
     * @param list 告警入参
     * @return 推送体列表，入参为空时返回空集合
     */
    List<AlarmVO> toAlarmVoList(List<AlarmCreateDTO> list);
}
