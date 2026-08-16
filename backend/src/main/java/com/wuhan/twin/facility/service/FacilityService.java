package com.wuhan.twin.facility.service;

import java.util.List;

import com.wuhan.twin.common.result.PageResult;
import com.wuhan.twin.facility.dto.FacilityPageQuery;
import com.wuhan.twin.facility.vo.FacilityPageVO;
import com.wuhan.twin.facility.vo.FacilityVO;

/**
 * 市政设施服务
 *
 * @author lvfan
 */
public interface FacilityService {

    /**
     * 查询设施列表
     *
     * @param facilityType 设施类型，为空表示不限
     * @param bbox         视口范围，格式 west,south,east,north，为空表示全域
     * @return 设施列表，无数据返回空集合
     * @throws com.wuhan.twin.common.exception.BizException 设施类型或 bbox 非法时抛出
     */
    List<FacilityVO> listFacilities(String facilityType, String bbox);

    /**
     * 设施分页
     *
     * @param query 分页与筛选参数
     * @return 分页结果
     * @throws com.wuhan.twin.common.exception.BizException 设施类型或状态非法时抛出
     */
    PageResult<FacilityPageVO> pageFacilities(FacilityPageQuery query);
}
