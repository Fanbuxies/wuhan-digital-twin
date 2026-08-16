package com.wuhan.twin.building.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
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

    /**
     * 统计建筑总数，供 3D Tiles 模式下状态卡展示
     *
     * @return 建筑数
     */
    Long countAll();

    /**
     * 建筑分页查询，附带中心点经纬度。keyword 为 null 表示不限，
     * buildingType 为 null 表示不限，由 service 层统一去空
     *
     * @param page         分页对象，由分页拦截器处理 count 与 limit
     * @param keyword      关键字，按名称模糊匹配
     * @param buildingType 建筑类型
     * @return 当页建筑，仅填充列表所需字段
     */
    IPage<BuildingDO> selectBuildingPage(IPage<BuildingDO> page,
                                         @Param("keyword") String keyword,
                                         @Param("buildingType") String buildingType);

    /**
     * 新增建筑，footprint 与 center 在 SQL 侧按中心点构造
     *
     * @param building   建筑入参，lon/lat 必填，id 由数据库 bigserial 回填
     * @param halfExtent footprint 半宽（度），沿中心点向四周扩张
     * @return 影响行数
     */
    int insertBuilding(@Param("building") BuildingDO building,
                       @Param("halfExtent") double halfExtent);

    /**
     * 按主键整体更新表单可编辑字段，几何按新中心点重建。
     * osm_id 与 base_altitude 属数据管线字段，不在此处更新
     *
     * @param building   建筑入参，lon/lat 必填
     * @param halfExtent footprint 半宽（度）
     * @return 影响行数
     */
    int updateBuilding(@Param("building") BuildingDO building,
                       @Param("halfExtent") double halfExtent);
}
