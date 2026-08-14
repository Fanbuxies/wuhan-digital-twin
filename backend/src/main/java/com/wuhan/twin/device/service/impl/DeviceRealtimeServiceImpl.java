package com.wuhan.twin.device.service.impl;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuhan.twin.common.exception.BizException;
import com.wuhan.twin.common.result.ResultCode;
import com.wuhan.twin.device.dto.DeviceMetricsDTO;
import com.wuhan.twin.device.entity.DeviceRealtimeDO;
import com.wuhan.twin.device.mapper.DeviceRealtimeMapper;
import com.wuhan.twin.device.mapper.DeviceTelemetryMapper;
import com.wuhan.twin.device.service.DeviceRealtimeService;
import com.wuhan.twin.device.vo.DeviceRealtimeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 设备实时状态服务实现
 *
 * @author lvfan
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceRealtimeServiceImpl implements DeviceRealtimeService {

    private final DeviceRealtimeMapper deviceRealtimeMapper;

    private final DeviceTelemetryMapper deviceTelemetryMapper;

    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveSnapshots(List<DeviceMetricsDTO> snapshots, boolean writeTelemetry) {
        if (CollectionUtils.isEmpty(snapshots)) {
            return;
        }
        deviceRealtimeMapper.batchUpsert(snapshots);
        if (writeTelemetry) {
            deviceTelemetryMapper.batchInsert(snapshots);
        }
    }

    @Override
    public DeviceRealtimeVO getRealtime(Long deviceId) {
        DeviceRealtimeDO realtime = deviceRealtimeMapper.selectByDeviceId(deviceId);
        if (realtime == null) {
            throw new BizException(ResultCode.NOT_FOUND, "设备暂无实时数据：" + deviceId);
        }
        DeviceRealtimeVO vo = new DeviceRealtimeVO();
        vo.setDeviceId(realtime.getDeviceId());
        vo.setMetrics(parseJson(realtime.getMetricsJson()));
        vo.setAlarmLevel(realtime.getAlarmLevel());
        vo.setTs(realtime.getUpdateTime());
        return vo;
    }

    @Override
    public List<DeviceRealtimeVO> toRealtimeVoList(List<DeviceMetricsDTO> snapshots) {
        if (CollectionUtils.isEmpty(snapshots)) {
            return Collections.emptyList();
        }
        return snapshots.stream().map(this::toVo).collect(Collectors.toList());
    }

    private DeviceRealtimeVO toVo(DeviceMetricsDTO snapshot) {
        DeviceRealtimeVO vo = new DeviceRealtimeVO();
        vo.setDeviceId(snapshot.getDeviceId());
        vo.setMetrics(parseJson(snapshot.getMetricsJson()));
        vo.setAlarmLevel(snapshot.getAlarmLevel());
        vo.setTs(snapshot.getTs());
        return vo;
    }

    /**
     * 把 jsonb 文本转成 JsonNode，避免响应体里出现转义后的字符串
     */
    private JsonNode parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.error("实时指标 JSON 无法解析，长度 {}", json.length(), e);
            throw new BizException(ResultCode.SYSTEM_ERROR);
        }
    }
}
