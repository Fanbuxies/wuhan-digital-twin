package com.wuhan.twin.device.service;

import java.util.List;

import com.wuhan.twin.device.dto.DeviceMetricsDTO;
import com.wuhan.twin.device.vo.DeviceRealtimeVO;

/**
 * 设备实时状态服务
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
     * 查询单设备实时状态
     *
     * @param deviceId 设备主键
     * @return 实时状态
     * @throws com.wuhan.twin.common.exception.BizException 无实时数据时抛出
     */
    DeviceRealtimeVO getRealtime(Long deviceId);

    /**
     * 把采样结果转成推送体，供 WebSocket 广播复用同一份 VO 结构
     *
     * @param snapshots 采样结果
     * @return 推送体列表，入参为空时返回空集合
     */
    List<DeviceRealtimeVO> toRealtimeVoList(List<DeviceMetricsDTO> snapshots);
}
