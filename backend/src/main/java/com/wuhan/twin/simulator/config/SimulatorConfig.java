package com.wuhan.twin.simulator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuhan.twin.alarm.service.AlarmService;
import com.wuhan.twin.common.config.AppProperties;
import com.wuhan.twin.device.mapper.DeviceMapper;
import com.wuhan.twin.device.service.DeviceRealtimeService;
import com.wuhan.twin.facility.mapper.FacilityMapper;
import com.wuhan.twin.simulator.DeviceSimulateTask;
import com.wuhan.twin.simulator.FacilitySimulateTask;
import com.wuhan.twin.ws.RealtimeWebSocketHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 模拟器装配。开关关闭时连调度线程都不创建
 *
 * @author lvfan
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.simulator", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class SimulatorConfig {

    @Bean
    public DeviceSimulateTask deviceSimulateTask(DeviceMapper deviceMapper,
                                                 DeviceRealtimeService deviceRealtimeService,
                                                 AlarmService alarmService,
                                                 RealtimeWebSocketHandler realtimeWebSocketHandler,
                                                 AppProperties appProperties,
                                                 ObjectMapper objectMapper) {
        return new DeviceSimulateTask(deviceMapper, deviceRealtimeService, alarmService,
                realtimeWebSocketHandler, appProperties, objectMapper);
    }

    @Bean
    public FacilitySimulateTask facilitySimulateTask(FacilityMapper facilityMapper,
                                                    DeviceRealtimeService deviceRealtimeService,
                                                    AlarmService alarmService,
                                                    RealtimeWebSocketHandler realtimeWebSocketHandler,
                                                    AppProperties appProperties,
                                                    ObjectMapper objectMapper) {
        return new FacilitySimulateTask(facilityMapper, deviceRealtimeService, alarmService,
                realtimeWebSocketHandler, appProperties, objectMapper);
    }
}
