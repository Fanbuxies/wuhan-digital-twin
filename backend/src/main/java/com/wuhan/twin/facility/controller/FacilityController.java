package com.wuhan.twin.facility.controller;

import java.util.List;

import com.wuhan.twin.common.enums.ObjectTypeEnum;
import com.wuhan.twin.common.result.PageResult;
import com.wuhan.twin.common.result.R;
import com.wuhan.twin.device.service.DeviceRealtimeService;
import com.wuhan.twin.device.vo.DeviceRealtimeVO;
import com.wuhan.twin.facility.dto.FacilityPageQuery;
import com.wuhan.twin.facility.service.FacilityService;
import com.wuhan.twin.facility.vo.FacilityPageVO;
import com.wuhan.twin.facility.vo.FacilityVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 市政设施接口
 *
 * @author lvfan
 */
@Tag(name = "市政设施", description = "室外充电桩、路灯、井盖、公交站台账")
@RestController
@RequestMapping("/api/facility")
@Validated
@RequiredArgsConstructor
public class FacilityController {

    private final FacilityService facilityService;

    private final DeviceRealtimeService deviceRealtimeService;

    @Operation(summary = "设施列表", description = "type 与 bbox 均为选填，缺省返回全域全部设施")
    @GetMapping("/list")
    public R<List<FacilityVO>> list(
            @Parameter(description = "设施类型：CHARGING_PILE / STREET_LAMP / MANHOLE / BUS_STOP")
            @RequestParam(required = false) String type,
            @Parameter(description = "视口范围，格式 west,south,east,north")
            @RequestParam(required = false) String bbox) {
        return R.ok(facilityService.listFacilities(type, bbox));
    }

    @Operation(summary = "设施分页",
            description = "管理端列表，关键字按名称或编号模糊匹配，筛选条件均选填")
    @GetMapping("/page")
    public R<PageResult<FacilityPageVO>> page(@Parameter(description = "分页与筛选参数")
                                              @Valid FacilityPageQuery query) {
        return R.ok(facilityService.pageFacilities(query));
    }

    @Operation(summary = "设施实时值", description = "设施尚无实时数据时返回 404")
    @GetMapping("/{id}/realtime")
    public R<DeviceRealtimeVO> realtime(
            @Parameter(description = "设施主键") @Min(1) @PathVariable Long id) {
        return R.ok(deviceRealtimeService.getRealtime(id, ObjectTypeEnum.FACILITY));
    }
}
