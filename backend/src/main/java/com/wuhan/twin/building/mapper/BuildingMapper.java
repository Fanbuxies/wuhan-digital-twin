package com.wuhan.twin.building.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuhan.twin.building.entity.BuildingDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 建筑 mapper
 *
 * <p>几何运算全部由 PostGIS 完成，Java 侧只接收 GeoJSON 文本与经纬度标量。</p>
 *
 * @author lvfan
 */
@Mapper
public interface BuildingMapper extends BaseMapper<BuildingDO> {

    /**
     * 按主键查详情，附带轮廓 GeoJSON 与中心点经纬度
     *
     * @param id 主键
     * @return 查无返回 null
     */
    BuildingDO selectDetailById(@Param("id") Long id);

    /**
     * 查询 GeoJSON FeatureCollection 文本。四个边界要么全为 null（不限范围），
     * 要么全部非 null，由 service 层保证
     *
     * @param west        西边界经度
     * @param south       南边界纬度
     * @param east        东边界经度
     * @param north       北边界纬度
     * @param maxFeatures 要素条数上限
     * @return FeatureCollection 的 JSON 文本，无数据时 features 为空数组
     */
    String selectGeoJson(@Param("west") Double west,
                         @Param("south") Double south,
                         @Param("east") Double east,
                         @Param("north") Double north,
                         @Param("maxFeatures") Integer maxFeatures);

    /**
     * 统计落在指定范围内的建筑数，用于判断是否触发条数截断
     *
     * @param west  西边界经度
     * @param south 南边界纬度
     * @param east  东边界经度
     * @param north 北边界纬度
     * @return 建筑数
     */
    Long countByBbox(@Param("west") Double west,
                     @Param("south") Double south,
                     @Param("east") Double east,
                     @Param("north") Double north);
}
