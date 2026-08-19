package com.wuhan.twin.building.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.wuhan.twin.building.dto.BuildingPageQuery;
import com.wuhan.twin.building.dto.BuildingSaveDTO;
import com.wuhan.twin.building.service.BuildingService;
import com.wuhan.twin.building.vo.BuildingDetailVO;
import com.wuhan.twin.building.vo.BuildingPageVO;
import com.wuhan.twin.building.vo.TilesetInfoVO;
import com.wuhan.twin.common.result.PageResult;
import com.wuhan.twin.common.result.R;
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
 * 建筑接口
 *
 * @author lvfan
 */
@Tag(name = "建筑", description = "三维底座数据源与建筑信息")
@RestController
@RequestMapping("/api/building")
@Validated
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
    public R<BuildingDetailVO> detail(@Parameter(description = "建筑主键") @Min(1) @PathVariable Long id) {
        return R.ok(buildingService.getDetail(id));
    }

    @Operation(summary = "建筑轮廓 GeoJSON",
            description = "bbox 必填，只服务当前视野；返回条数受 app.building.geojson-max-features 限制")
    @GetMapping("/geojson")
    public R<JsonNode> geoJson(
            @Parameter(description = "视口范围，格式 west,south,east,north，必填")
            @RequestParam(required = false) String bbox) {
        return R.ok(buildingService.getGeoJson(bbox));
    }

    @Operation(summary = "建筑分页",
            description = "管理端列表，关键字按名称模糊匹配；记录只带中心点经纬度，不带轮廓几何")
    @GetMapping("/page")
    public R<PageResult<BuildingPageVO>> page(@Parameter(description = "分页与筛选参数")
                                              @Valid BuildingPageQuery query) {
        return R.ok(buildingService.pageBuildings(query));
    }

    @Operation(summary = "新增建筑",
            description = "footprint 由服务端按中心点生成近似矩形，真实轮廓待测绘数据接入后替换")
    @PostMapping
    public R<Long> create(@Parameter(description = "建筑入参") @Valid @RequestBody BuildingSaveDTO dto) {
        return R.ok(buildingService.createBuilding(dto));
    }

    @Operation(summary = "编辑建筑",
            description = "整体更新表单可编辑字段，几何按新中心点重建；osm_id 等数据管线字段不受影响")
    @PutMapping("/{id}")
    public R<Void> update(@Parameter(description = "建筑主键") @Min(1) @PathVariable Long id,
                          @Parameter(description = "建筑入参") @Valid @RequestBody BuildingSaveDTO dto) {
        buildingService.updateBuilding(id, dto);
        return R.ok();
    }

    @Operation(summary = "删除建筑",
            description = "建筑下仍有设备时拒绝删除，引用完整性由应用层保证")
    @DeleteMapping("/{id}")
    public R<Void> delete(@Parameter(description = "建筑主键") @Min(1) @PathVariable Long id) {
        buildingService.deleteBuilding(id);
        return R.ok();
    }
}
