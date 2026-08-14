package com.wuhan.twin.building.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.wuhan.twin.building.service.BuildingService;
import com.wuhan.twin.building.vo.BuildingDetailVO;
import com.wuhan.twin.building.vo.TilesetInfoVO;
import com.wuhan.twin.common.result.R;
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
 * 建筑接口
 *
 * @author lvfan
 */
@Tag(name = "建筑", description = "三维底座数据源与建筑信息")
@RestController
@RequestMapping("/api/building")
@RequiredArgsConstructor
public class BuildingController {

    private final BuildingService buildingService;

    @Operation(summary = "3D Tiles 地址与初始视角",
            description = "tilesetUrl 为 null 表示尚未生成 3D Tiles，前端改用 GeoJSON 拉伸白模")
    @GetMapping("/tileset-info")
    public R<TilesetInfoVO> tilesetInfo() {
        return R.ok(buildingService.getTilesetInfo());
    }

    @Operation(summary = "建筑详情")
    @GetMapping("/{id}")
    public R<BuildingDetailVO> detail(@Parameter(description = "建筑主键") @PathVariable Long id) {
        return R.ok(buildingService.getDetail(id));
    }

    @Operation(summary = "建筑轮廓 GeoJSON",
            description = "bbox 选填，缺省返回全域；返回条数受 app.building.geojson-max-features 限制")
    @GetMapping("/geojson")
    public R<JsonNode> geoJson(
            @Parameter(description = "视口范围，格式 west,south,east,north") @RequestParam(required = false) String bbox) {
        return R.ok(buildingService.getGeoJson(bbox));
    }
}
