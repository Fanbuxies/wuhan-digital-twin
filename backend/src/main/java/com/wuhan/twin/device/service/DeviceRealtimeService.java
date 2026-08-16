package com.wuhan.twin.device.service;

import java.util.List;

import com.wuhan.twin.common.enums.ObjectTypeEnum;
import com.wuhan.twin.device.dto.DeviceMetricsDTO;
import com.wuhan.twin.device.vo.DeviceRealtimeVO;

/**
 * 监测对象实时状态服务，设备与市政设施共用
 *
 * @author lvfan
 */
public interface DeviceRealtimeService {

    /**
     * 保存一批采样结果
     *
     * @param snapshots      采样结果，空集合直接返回
     * @param writeTelemetry 是否同时落历史遥测。历史表按较低频率写入，由调用方控制节奏
     */
    void saveSnapshots(List<DeviceMetricsDTO> snapshots, boolean writeTelemetry);

    /**
     * 查询单个监测对象的实时状态
     *
     * @param objectId   监测对象主键
     * @param objectType 监测对象类型，设备与设施共用同一张实时表，须显式区分
     * @return 实时状态
     * @throws com.wuhan.twin.common.exception.BizException 无实时数据时抛出
     */
    DeviceRealtimeVO getRealtime(Long objectId, ObjectTypeEnum objectType);

    /**
     * 把采样结果转成推送体，供 WebSocket 广播复用同一份 VO 结构
     *
     * @param snapshots 采样结果
     * @return 推送体列表，入参为空时返回空集合
     */
    List<DeviceRealtimeVO> toRealtimeVoList(List<DeviceMetricsDTO> snapshots);
}
