package com.wuhan.twin.building.service.impl;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wuhan.twin.building.dto.BuildingPageQuery;
import com.wuhan.twin.building.dto.BuildingSaveDTO;
import com.wuhan.twin.building.entity.BuildingDO;
import com.wuhan.twin.building.mapper.BuildingMapper;
import com.wuhan.twin.building.service.BuildingService;
import com.wuhan.twin.building.vo.BuildingDetailVO;
import com.wuhan.twin.building.vo.BuildingPageVO;
import com.wuhan.twin.building.vo.TilesetInfoVO;
import com.wuhan.twin.common.config.AppProperties;
import com.wuhan.twin.common.exception.BizException;
import com.wuhan.twin.common.result.PageResult;
import com.wuhan.twin.common.result.ResultCodeEnum;
import com.wuhan.twin.common.util.BboxUtils;
import com.wuhan.twin.device.entity.DeviceDO;
import com.wuhan.twin.device.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 建筑服务实现
 *
 * <p>所有几何计算交给 PostGIS，本类只做参数校验与 JSON 文本解析。</p>
 *
 * @author lvfan
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildingServiceImpl implements BuildingService {

    /**
     * 管理端新增建筑的 footprint 半宽（度）。武汉纬度下约合 19 米，
     * 生成的近似矩形约 20 米见方，待接入真实测绘轮廓后再替换
     */
    private static final double FOOTPRINT_HALF_EXTENT = 0.0001;

    /**
     * 高度来源合法取值，与 t_building.height_source 的 CHECK 约束一致
     */
    private static final Set<String> HEIGHT_SOURCES =
            Set.of("osm_height", "osm_levels", "default_by_type");

    private final BuildingMapper buildingMapper;

    private final DeviceMapper deviceMapper;

    private final AppProperties appProperties;

    private final ObjectMapper objectMapper;

    @Override
    public TilesetInfoVO getTilesetInfo() {
        AppProperties.Tileset tileset = appProperties.getTileset();
        TilesetInfoVO.CameraVO camera = new TilesetInfoVO.CameraVO();
        BeanUtils.copyProperties(tileset.getCamera(), camera);
        TilesetInfoVO vo = new TilesetInfoVO();
        // 配置留空时统一对外返回 null，前端据此走 GeoJSON 降级
        vo.setTilesetUrl(StringUtils.hasText(tileset.getUrl()) ? tileset.getUrl() : null);
        vo.setBuildingCount(buildingMapper.countAll());
        vo.setCamera(camera);
        return vo;
    }

    @Override
    public BuildingDetailVO getDetail(Long id) {
        BuildingDO building = buildingMapper.selectDetailById(id);
        if (building == null) {
            throw new BizException(ResultCodeEnum.NOT_FOUND, "建筑不存在：" + id);
        }
        BuildingDetailVO vo = new BuildingDetailVO();
        BeanUtils.copyProperties(building, vo);
        vo.setFootprint(parseJson(building.getFootprintGeoJson()));
        return vo;
    }

    @Override
    public JsonNode getGeoJson(String bbox) {
        BboxUtils.Bbox range = BboxUtils.parse(bbox);
        Integer maxFeatures = appProperties.getBuilding().getGeojsonMaxFeatures();
        Long total = buildingMapper.countByBbox(range.west(), range.south(), range.east(), range.north());
        if (total != null && total == 0) {
            return emptyFeatureCollection();
        }
        if (total != null && total > maxFeatures) {
            log.warn("GeoJSON 命中条数上限，bbox={}，实际 {} 条，仅返回前 {} 条",
                    bbox, total, maxFeatures);
        }
        String geoJson = buildingMapper.selectGeoJson(
                range.west(), range.south(), range.east(), range.north(), maxFeatures);
        return parseJson(geoJson);
    }

    @Override
    public PageResult<BuildingPageVO> pageBuildings(BuildingPageQuery query) {
        Page<BuildingDO> page = new Page<>(query.getCurrent(), query.getSize());
        IPage<BuildingDO> result = buildingMapper.selectBuildingPage(
                page, trimToNull(query.getKeyword()), trimToNull(query.getBuildingType()));
        List<BuildingPageVO> records = result.getRecords().stream()
                .map(BuildingServiceImpl::toPageVo)
                .collect(Collectors.toList());
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private static BuildingPageVO toPageVo(BuildingDO building) {
        BuildingPageVO vo = new BuildingPageVO();
        BeanUtils.copyProperties(building, vo);
        return vo;
    }

    @Override
    public Long createBuilding(BuildingSaveDTO dto) {
        normalizeHeightSource(dto.getHeightSource());
        BuildingDO building = new BuildingDO();
        BeanUtils.copyProperties(dto, building);
        // BeanUtils 不做 BigDecimal 到 Double 的转换，lon/lat 手动拷贝
        building.setLon(dto.getLon().doubleValue());
        building.setLat(dto.getLat().doubleValue());
        buildingMapper.insertBuilding(building, FOOTPRINT_HALF_EXTENT);
        return building.getId();
    }

    @Override
    public void updateBuilding(Long id, BuildingSaveDTO dto) {
        requireBuildingExists(id);
        normalizeHeightSource(dto.getHeightSource());
        BuildingDO building = new BuildingDO();
        BeanUtils.copyProperties(dto, building);
        building.setId(id);
        building.setLon(dto.getLon().doubleValue());
        building.setLat(dto.getLat().doubleValue());
        if (buildingMapper.updateBuilding(building, FOOTPRINT_HALF_EXTENT) == 0) {
            // 存在性检查与更新之间的并发删除窗口极小，防御性兜底
            throw new BizException(ResultCodeEnum.NOT_FOUND, "建筑不存在：" + id);
        }
    }

    @Override
    public void deleteBuilding(Long id) {
        requireBuildingExists(id);
        Long deviceCount = deviceMapper.selectCount(
                Wrappers.<DeviceDO>lambdaQuery().eq(DeviceDO::getBuildingId, id));
        if (deviceCount != null && deviceCount > 0) {
            throw new BizException("该建筑下存在 " + deviceCount + " 台设备，无法删除");
        }
        buildingMapper.deleteById(id);
    }

    /**
     * 建筑存在性检查，不存在抛 404
     */
    private void requireBuildingExists(Long id) {
        if (buildingMapper.selectById(id) == null) {
            throw new BizException(ResultCodeEnum.NOT_FOUND, "建筑不存在：" + id);
        }
    }

    /**
     * 校验高度来源取值，非法时抛出参数错误
     */
    private static void normalizeHeightSource(String heightSource) {
        if (!HEIGHT_SOURCES.contains(heightSource)) {
            String supported = String.join("/", HEIGHT_SOURCES);
            throw new BizException(ResultCodeEnum.PARAM_ERROR, "高度来源不支持，可选值：" + supported);
        }
    }

    /**
     * 空白关键字与类型统一归一为 null，交给 SQL 的 if 判断
     */
    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 范围内无建筑时直接返回空 FeatureCollection，不再查库拼装
     */
    private JsonNode emptyFeatureCollection() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "FeatureCollection");
        root.putArray("features");
        return root;
    }

    /**
     * 解析 PostGIS 生成的 JSON 文本，仅做文本转对象，不涉及几何运算
     */
    private JsonNode parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.error("PostGIS 返回的 GeoJSON 无法解析，长度 {}", json.length(), e);
            throw new BizException(ResultCodeEnum.SYSTEM_ERROR);
        }
    }

    /**
     * bbox 四个边界，四值同时有效或同时为 null
     *
     * @param west  西边界经度
     * @param south 南边界纬度
     * @param east  东边界经度
     * @param north 北边界纬度
     */
    private record Bbox(Double west, Double south, Double east, Double north) {
    }
}
