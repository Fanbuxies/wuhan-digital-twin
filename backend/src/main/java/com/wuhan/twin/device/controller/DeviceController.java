package com.wuhan.twin.device.controller;

import java.util.List;

import com.wuhan.twin.common.result.R;
import com.wuhan.twin.device.service.DeviceRealtimeService;
import com.wuhan.twin.device.service.DeviceService;
import com.wuhan.twin.device.vo.DeviceRealtimeVO;
import com.wuhan.twin.device.vo.DeviceVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备接口
 *
 * @author lvfan
 */
@Tag(name = "设备", description = "楼宇物联网设备台账")
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    private final DeviceRealtimeService deviceRealtimeService;

    @Operation(summary = "设备列表", description = "buildingId 与 type 均为选填，缺省返回全部设备")
    @GetMapping("/list")
    public R<List<DeviceVO>> list(
            @Parameter(description = "所属建筑主键") @RequestParam(required = false) Long buildingId,
            @Parameter(description = "设备类型：SMOKE / WATER / TEMP_HUMI / ELECTRIC / CAMERA")
            @RequestParam(required = false) String type) {
        return R.ok(deviceService.listDevices(buildingId, type));
    }

    @Operation(summary = "设备实时值", description = "设备尚无实时数据时返回 404")
    @GetMapping("/{id}/realtime")
    public R<DeviceRealtimeVO> realtime(
            @Parameter(description = "设备主键") @PathVariable Long id) {
        return R.ok(deviceRealtimeService.getRealtime(id));
    }
}
