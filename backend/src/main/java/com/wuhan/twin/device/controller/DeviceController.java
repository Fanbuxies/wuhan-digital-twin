package com.wuhan.twin.device.controller;

import java.util.List;

import com.wuhan.twin.common.enums.ObjectTypeEnum;
import com.wuhan.twin.common.result.PageResult;
import com.wuhan.twin.common.result.R;
import com.wuhan.twin.device.dto.DevicePageQuery;
import com.wuhan.twin.device.dto.DeviceSaveDTO;
import com.wuhan.twin.device.service.DeviceRealtimeService;
import com.wuhan.twin.device.service.DeviceService;
import com.wuhan.twin.device.vo.DevicePageVO;
import com.wuhan.twin.device.vo.DeviceRealtimeVO;
import com.wuhan.twin.device.vo.DeviceVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
@Validated
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
            @Parameter(description = "设备主键") @Min(1) @PathVariable Long id) {
        return R.ok(deviceRealtimeService.getRealtime(id, ObjectTypeEnum.DEVICE));
    }

    @Operation(summary = "设备分页",
            description = "管理端列表，关键字按名称或编号模糊匹配，筛选条件均选填")
    @GetMapping("/page")
    public R<PageResult<DevicePageVO>> page(@Parameter(description = "分页与筛选参数")
                                            @Valid DevicePageQuery query) {
        return R.ok(deviceService.pageDevices(query));
    }

    @Operation(summary = "新增设备",
            description = "location 由服务端按点位经纬度构造；在线设备会被模拟器自动接管产生实时数据")
    @PostMapping
    public R<Long> create(@Parameter(description = "设备入参") @Valid @RequestBody DeviceSaveDTO dto) {
        return R.ok(deviceService.createDevice(dto));
    }

    @Operation(summary = "编辑设备",
            description = "整体更新表单可编辑字段，location 按新点位重建；install_time 等字段不受影响")
    @PutMapping("/{id}")
    public R<Void> update(@Parameter(description = "设备主键") @Min(1) @PathVariable Long id,
                          @Parameter(description = "设备入参") @Valid @RequestBody DeviceSaveDTO dto) {
        deviceService.updateDevice(id, dto);
        return R.ok();
    }

    @Operation(summary = "删除设备",
            description = "事务内连带清理该设备的实时状态、告警与历史遥测")
    @DeleteMapping("/{id}")
    public R<Void> delete(@Parameter(description = "设备主键") @Min(1) @PathVariable Long id) {
        deviceService.deleteDevice(id);
        return R.ok();
    }
}
