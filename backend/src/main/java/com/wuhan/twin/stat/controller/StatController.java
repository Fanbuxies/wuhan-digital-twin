package com.wuhan.twin.stat.controller;

import com.wuhan.twin.common.result.R;
import com.wuhan.twin.stat.service.StatService;
import com.wuhan.twin.stat.vo.StatOverviewVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 概览统计接口
 *
 * @author lvfan
 */
@Tag(name = "概览", description = "首页概览指标")
@RestController
@RequestMapping("/api/stat")
@RequiredArgsConstructor
public class StatController {

    private final StatService statService;

    @Operation(summary = "概览指标", description = "设备总数、在线设备数、待处理告警数")
    @GetMapping("/overview")
    public R<StatOverviewVO> overview() {
        return R.ok(statService.getOverview());
    }
}
