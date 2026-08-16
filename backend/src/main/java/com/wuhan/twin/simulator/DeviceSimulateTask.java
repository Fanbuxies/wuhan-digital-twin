package com.wuhan.twin.simulator;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuhan.twin.alarm.dto.AlarmCreateDTO;
import com.wuhan.twin.alarm.service.AlarmService;
import com.wuhan.twin.alarm.vo.AlarmVO;
import com.wuhan.twin.common.config.AppProperties;
import com.wuhan.twin.common.enums.ObjectTypeEnum;
import com.wuhan.twin.device.dto.DeviceMetricsDTO;
import com.wuhan.twin.device.entity.DeviceDO;
import com.wuhan.twin.device.enums.DeviceTypeEnum;
import com.wuhan.twin.device.mapper.DeviceMapper;
import com.wuhan.twin.device.service.DeviceRealtimeService;
import com.wuhan.twin.device.vo.DeviceRealtimeVO;
import com.wuhan.twin.ws.RealtimeWebSocketHandler;
import com.wuhan.twin.ws.vo.PushMessageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.CollectionUtils;

/**
 * 设备数据模拟任务：周期生成在线设备的波动值，落实时表与历史表，并向前端广播
 *
 * <p>仅由 SimulatorConfig 在开关打开时注册，故本类不加组件注解。</p>
 *
 * @author lvfan
 */
@Slf4j
@RequiredArgsConstructor
public class DeviceSimulateTask {

    private final DeviceMapper deviceMapper;

    private final DeviceRealtimeService deviceRealtimeService;

    private final AlarmService alarmService;

    private final RealtimeWebSocketHandler realtimeWebSocketHandler;

    private final AppProperties appProperties;

    private final ObjectMapper objectMapper;

    /**
     * 调度次数计数器，用于按间隔决定是否落历史遥测
     */
    private final AtomicLong tickCounter = new AtomicLong();

    /**
     * 单轮模拟。调度线程抛出的异常不会外显，故此处兜底捕获并打完整堆栈
     */
    @Scheduled(fixedRateString = "${app.simulator.fixed-rate}")
    public void simulate() {
        try {
            doSimulate();
        } catch (Exception e) {
            log.error("设备数据模拟失败，本轮跳过", e);
        }
    }

    private void doSimulate() {
        List<DeviceDO> devices = deviceMapper.selectOnlineDevices();
        if (CollectionUtils.isEmpty(devices)) {
            log.warn("无在线设备，模拟器空转");
            return;
        }
        Set<Long> pendingDeviceIds = alarmService.listPendingDeviceIds(ObjectTypeEnum.DEVICE);
        OffsetDateTime now = OffsetDateTime.now();
        double alarmProbability = appProperties.getSimulator().getAlarmProbability();

        List<DeviceMetricsDTO> snapshots = new ArrayList<>(devices.size());
        List<AlarmCreateDTO> alarms = new ArrayList<>(devices.size());
        for (DeviceDO device : devices) {
            Optional<DeviceTypeEnum> typeEnum = DeviceTypeEnum.of(device.getDeviceType());
            if (typeEnum.isEmpty()) {
                log.warn("设备 {} 类型 {} 不在枚举内，跳过模拟", device.getId(), device.getDeviceType());
                continue;
            }
            // 已有待处理告警的设备只生成正常值，避免同一设备反复刷告警
            boolean alarmAllowed = !pendingDeviceIds.contains(device.getId());
            boolean triggerAlarm = alarmAllowed
                    && ThreadLocalRandom.current().nextDouble() < alarmProbability;
            MetricsGenerator.Sample sample = MetricsGenerator.generate(typeEnum.get(), triggerAlarm);
            String metricsJson = writeJson(sample.getMetrics());
            if (metricsJson == null) {
                continue;
            }
            snapshots.add(buildSnapshot(device.getId(), metricsJson, sample.getAlarmLevel(), now));
            if (sample.alarmed()) {
                alarms.add(buildAlarm(device.getId(), sample, metricsJson, now));
            }
        }
        if (snapshots.isEmpty()) {
            return;
        }

        boolean writeTelemetry = shouldWriteTelemetry();
        deviceRealtimeService.saveSnapshots(snapshots, writeTelemetry);
        alarmService.createAlarms(alarms);

        List<DeviceRealtimeVO> pushData = deviceRealtimeService.toRealtimeVoList(snapshots);
        realtimeWebSocketHandler.broadcast(PushMessageVO.TYPE_DEVICE_UPDATE, pushData);
        for (AlarmVO alarm : alarmService.toAlarmVoList(alarms)) {
            realtimeWebSocketHandler.broadcast(PushMessageVO.TYPE_ALARM_NEW, alarm);
        }
        if (!alarms.isEmpty()) {
            log.debug("本轮模拟 {} 台设备，新增告警 {} 条", snapshots.size(), alarms.size());
        }
    }

    /**
     * 按配置的调度间隔判断本轮是否落历史遥测
     */
    private boolean shouldWriteTelemetry() {
        int interval = appProperties.getSimulator().getTelemetryTickInterval();
        long tick = tickCounter.incrementAndGet();
        return interval > 0 && tick % interval == 0;
    }

    private static DeviceMetricsDTO buildSnapshot(Long deviceId, String metricsJson,
                                                  Integer alarmLevel, OffsetDateTime ts) {
        DeviceMetricsDTO dto = new DeviceMetricsDTO();
        dto.setDeviceId(deviceId);
        dto.setObjectType(ObjectTypeEnum.DEVICE.name());
        dto.setMetricsJson(metricsJson);
        dto.setAlarmLevel(alarmLevel);
        dto.setTs(ts);
        return dto;
    }

    private static AlarmCreateDTO buildAlarm(Long deviceId, MetricsGenerator.Sample sample,
                                             String metricsJson, OffsetDateTime occurTime) {
        AlarmCreateDTO dto = new AlarmCreateDTO();
        dto.setDeviceId(deviceId);
        dto.setObjectType(ObjectTypeEnum.DEVICE.name());
        dto.setAlarmType(sample.getAlarmType());
        dto.setAlarmLevel(sample.getAlarmLevel());
        dto.setAlarmValueJson(metricsJson);
        dto.setOccurTime(occurTime);
        return dto;
    }

    /**
     * 序列化指标。单台设备序列化失败不影响整轮，返回 null 由调用方跳过
     */
    private String writeJson(Object metrics) {
        try {
            return objectMapper.writeValueAsString(metrics);
        } catch (JsonProcessingException e) {
            log.error("模拟指标序列化失败", e);
            return null;
        }
    }
}
