package com.wuhan.twin.building.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuhan.twin.building.entity.BuildingDO;
import com.wuhan.twin.building.mapper.BuildingMapper;
import com.wuhan.twin.building.service.BuildingService;
import com.wuhan.twin.building.vo.BuildingDetailVO;
import com.wuhan.twin.building.vo.TilesetInfoVO;
import com.wuhan.twin.common.config.AppProperties;
import com.wuhan.twin.common.exception.BizException;
import com.wuhan.twin.common.result.ResultCode;
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
     * bbox 参数的分段数：west,south,east,north
     */
    private static final int BBOX_PART_COUNT = 4;

    private static final String BBOX_SEPARATOR = ",";

    private static final double LON_MIN = -180.0D;

    private static final double LON_MAX = 180.0D;

    private static final double LAT_MIN = -90.0D;

    private static final double LAT_MAX = 90.0D;

    private final BuildingMapper buildingMapper;

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
        vo.setCamera(camera);
        return vo;
    }

    @Override
    public BuildingDetailVO getDetail(Long id) {
        BuildingDO building = buildingMapper.selectDetailById(id);
        if (building == null) {
            throw new BizException(ResultCode.NOT_FOUND, "建筑不存在：" + id);
        }
        BuildingDetailVO vo = new BuildingDetailVO();
        BeanUtils.copyProperties(building, vo);
        vo.setFootprint(parseJson(building.getFootprintGeoJson()));
        return vo;
    }

    @Override
    public JsonNode getGeoJson(String bbox) {
        Bbox range = parseBbox(bbox);
        Integer maxFeatures = appProperties.getBuilding().getGeojsonMaxFeatures();
        Long total = buildingMapper.countByBbox(range.west(), range.south(), range.east(), range.north());
        if (total != null && total > maxFeatures) {
            log.warn("GeoJSON 命中条数上限，bbox={}，实际 {} 条，仅返回前 {} 条", bbox, total, maxFeatures);
        }
        String geoJson = buildingMapper.selectGeoJson(
                range.west(), range.south(), range.east(), range.north(), maxFeatures);
        return parseJson(geoJson);
    }

    /**
     * 解析并校验 bbox，为空时返回四个 null 表示不限范围
     */
    private Bbox parseBbox(String bbox) {
        if (!StringUtils.hasText(bbox)) {
            return new Bbox(null, null, null, null);
        }
        String[] parts = bbox.split(BBOX_SEPARATOR);
        if (parts.length != BBOX_PART_COUNT) {
            throw new BizException(ResultCode.PARAM_ERROR, "bbox 需为 west,south,east,north 四个数值");
        }
        double[] values = new double[BBOX_PART_COUNT];
        for (int i = 0; i < BBOX_PART_COUNT; i++) {
            try {
                values[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new BizException(ResultCode.PARAM_ERROR, "bbox 含非数值内容：" + parts[i].trim());
            }
        }
        double west = values[0];
        double south = values[1];
        double east = values[2];
        double north = values[3];
        boolean lonInRange = west >= LON_MIN && west <= LON_MAX && east >= LON_MIN && east <= LON_MAX;
        boolean latInRange = south >= LAT_MIN && south <= LAT_MAX && north >= LAT_MIN && north <= LAT_MAX;
        if (!lonInRange || !latInRange) {
            throw new BizException(ResultCode.PARAM_ERROR, "bbox 经纬度超出取值范围");
        }
        if (west >= east || south >= north) {
            throw new BizException(ResultCode.PARAM_ERROR, "bbox 需满足 west < east 且 south < north");
        }
        return new Bbox(west, south, east, north);
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
            throw new BizException(ResultCode.SYSTEM_ERROR);
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
