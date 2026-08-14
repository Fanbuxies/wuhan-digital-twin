package com.wuhan.twin.building.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.wuhan.twin.building.vo.BuildingDetailVO;
import com.wuhan.twin.building.vo.TilesetInfoVO;

/**
 * 建筑服务
 *
 * @author lvfan
 */
public interface BuildingService {

    /**
     * 获取 3D Tiles 地址与初始视角
     *
     * @return tilesetUrl 未配置时为 null
     */
    TilesetInfoVO getTilesetInfo();

    /**
     * 查询建筑详情
     *
     * @param id 主键
     * @return 建筑详情
     * @throws com.wuhan.twin.common.exception.BizException 建筑不存在时抛出
     */
    BuildingDetailVO getDetail(Long id);

    /**
     * 查询建筑轮廓 GeoJSON
     *
     * @param bbox 视口范围，格式 west,south,east,north，为空表示全域
     * @return GeoJSON FeatureCollection
     * @throws com.wuhan.twin.common.exception.BizException bbox 格式或取值非法时抛出
     */
    JsonNode getGeoJson(String bbox);
}
