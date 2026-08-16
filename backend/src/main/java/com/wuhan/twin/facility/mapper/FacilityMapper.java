package com.wuhan.twin.facility.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuhan.twin.facility.entity.FacilityDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 市政设施 mapper
 *
 * @author lvfan
 */
@Mapper
public interface FacilityMapper extends BaseMapper<FacilityDO> {

    /**
     * 按类型与视口范围查设施列表，附带点位经纬度
     *
     * @param facilityType 设施类型，为 null 表示不限
     * @param west         西边界经度，四个边界同时为 null 表示不限范围
     * @param south        南边界纬度
     * @param east         东边界经度
     * @param north        北边界纬度
     * @return 设施列表
     */
    List<FacilityDO> selectFacilityList(@Param("facilityType") String facilityType,
                                        @Param("west") Double west,
                                        @Param("south") Double south,
                                        @Param("east") Double east,
                                        @Param("north") Double north);

    /**
     * 查询在线设施的主键与类型，供模拟器遍历。离线与故障设施不产生实时数据
     *
     * @return 在线设施列表，仅填充 id 与 facilityType
     */
    List<FacilityDO> selectOnlineFacilities();

    /**
     * 设施分页，关键字按名称或编号模糊匹配，类型与状态筛选均选填
     *
     * @param page         分页参数，count 为 0 时拦截器直接返回空列表
     * @param keyword      关键字，为 null 表示不限
     * @param facilityType 设施类型，为 null 表示不限
     * @param status       运行状态，为 null 表示不限
     * @return 分页结果，记录含点位经纬度
     */
    IPage<FacilityDO> selectFacilityPage(Page<FacilityDO> page,
                                         @Param("keyword") String keyword,
                                         @Param("facilityType") String facilityType,
                                         @Param("status") String status);
}
