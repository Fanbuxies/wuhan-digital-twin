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
import com.wuhan.twin.device.service.DeviceRealtimeService;
import com.wuhan.twin.device.vo.DeviceRealtimeVO;
import com.wuhan.twin.facility.entity.FacilityDO;
import com.wuhan.twin.facility.enums.FacilityTypeEnum;
import com.wuhan.twin.facility.mapper.FacilityMapper;
import com.wuhan.twin.ws.RealtimeWebSocketHandler;
import com.wuhan.twin.ws.vo.PushMessageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.CollectionUtils;

/**
 * 市政设施数据模拟任务：周期生成在线设施的波动值，落实时表与历史表，并向前端广播
 *
 * <p>与设备任务共用 t_device_realtime / t_device_telemetry / t_alarm，
 * 靠 object_type = FACILITY 区分，故两张台账表的 id 空间可以各自从 1 开始。</p>
 *
 * <p>仅由 SimulatorConfig 在开关打开时注册，故本类不加组件注解。</p>
 *
 * @author lvfan
 */
@Slf4j
@RequiredArgsConstructor
public class FacilitySimulateTask {

    private final FacilityMapper facilityMapper;

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
    @Scheduled(fixedRateString = "${app.simulator.facility-fixed-rate}")
    public void simulate() {
        try {
            doSimulate();
        } catch (Exception e) {
            log.error("市政设施数据模拟失败，本轮跳过", e);
        }
    }

    private void doSimulate() {
        List<FacilityDO> facilities = facilityMapper.selectOnlineFacilities();
        if (CollectionUtils.isEmpty(facilities)) {
            // t_facility 尚未导入数据时属正常状态，不刷 warn 日志
            log.debug("无在线市政设施，模拟器空转");
            return;
        }
        Set<Long> pendingFacilityIds = alarmService.listPendingDeviceIds(ObjectTypeEnum.FACILITY);
        OffsetDateTime now = OffsetDateTime.now();
        double alarmProbability = appProperties.getSimulator().getFacilityAlarmProbability();

        List<DeviceMetricsDTO> snapshots = new ArrayList<>(facilities.size());
        List<AlarmCreateDTO> alarms = new ArrayList<>(facilities.size());
        for (FacilityDO facility : facilities) {
            Optional<FacilityTypeEnum> typeEnum = FacilityTypeEnum.of(facility.getFacilityType());
            if (typeEnum.isEmpty()) {
                log.warn("设施 {} 类型 {} 不在枚举内，跳过模拟", facility.getId(), facility.getFacilityType());
                continue;
            }
            // 已有待处理告警的设施只生成正常值，避免同一设施反复刷告警
            boolean alarmAllowed = !pendingFacilityIds.contains(facility.getId());
            boolean triggerAlarm = alarmAllowed
                    && ThreadLocalRandom.current().nextDouble() < alarmProbability;
            MetricsGenerator.Sample sample = FacilityMetricsGenerator.generate(typeEnum.get(), triggerAlarm);
            String metricsJson = writeJson(sample.getMetrics());
            if (metricsJson == null) {
                continue;
            }
            snapshots.add(buildSnapshot(facility.getId(), metricsJson, sample.getAlarmLevel(), now));
            if (sample.alarmed()) {
                alarms.add(buildAlarm(facility.getId(), sample, metricsJson, now));
            }
        }
        if (snapshots.isEmpty()) {
            return;
        }

        boolean writeTelemetry = shouldWriteTelemetry();
        deviceRealtimeService.saveSnapshots(snapshots, writeTelemetry);
        alarmService.createAlarms(alarms);

        List<DeviceRealtimeVO> pushData = deviceRealtimeService.toRealtimeVoList(snapshots);
        realtimeWebSocketHandler.broadcast(PushMessageVO.TYPE_FACILITY_UPDATE, pushData);
        for (AlarmVO alarm : alarmService.toAlarmVoList(alarms)) {
            realtimeWebSocketHandler.broadcast(PushMessageVO.TYPE_ALARM_NEW, alarm);
        }
        if (!alarms.isEmpty()) {
            log.debug("本轮模拟 {} 个设施，新增告警 {} 条", snapshots.size(), alarms.size());
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

    private static DeviceMetricsDTO buildSnapshot(Long facilityId, String metricsJson,
                                                  Integer alarmLevel, OffsetDateTime ts) {
        DeviceMetricsDTO dto = new DeviceMetricsDTO();
        dto.setDeviceId(facilityId);
        dto.setObjectType(ObjectTypeEnum.FACILITY.name());
        dto.setMetricsJson(metricsJson);
        dto.setAlarmLevel(alarmLevel);
        dto.setTs(ts);
        return dto;
    }

    private static AlarmCreateDTO buildAlarm(Long facilityId, MetricsGenerator.Sample sample,
                                             String metricsJson, OffsetDateTime occurTime) {
        AlarmCreateDTO dto = new AlarmCreateDTO();
        dto.setDeviceId(facilityId);
        dto.setObjectType(ObjectTypeEnum.FACILITY.name());
        dto.setAlarmType(sample.getAlarmType());
        dto.setAlarmLevel(sample.getAlarmLevel());
        dto.setAlarmValueJson(metricsJson);
        dto.setOccurTime(occurTime);
        return dto;
    }

    /**
     * 序列化指标。单个设施序列化失败不影响整轮，返回 null 由调用方跳过
     */
    private String writeJson(Object metrics) {
        try {
            return objectMapper.writeValueAsString(metrics);
        } catch (JsonProcessingException e) {
            log.error("设施模拟指标序列化失败", e);
            return null;
        }
    }
}
