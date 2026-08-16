package com.wuhan.twin.facility.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuhan.twin.common.exception.BizException;
import com.wuhan.twin.common.result.PageResult;
import com.wuhan.twin.common.result.ResultCodeEnum;
import com.wuhan.twin.common.util.BboxUtils;
import com.wuhan.twin.facility.dto.FacilityPageQuery;
import com.wuhan.twin.facility.entity.FacilityDO;
import com.wuhan.twin.facility.enums.FacilityTypeEnum;
import com.wuhan.twin.facility.mapper.FacilityMapper;
import com.wuhan.twin.facility.service.FacilityService;
import com.wuhan.twin.facility.vo.FacilityPageVO;
import com.wuhan.twin.facility.vo.FacilityVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 市政设施服务实现
 *
 * <p>范围过滤交给 PostGIS 的 ST_Intersects，本类只做参数校验与视图组装。</p>
 *
 * @author lvfan
 */
@Service
@RequiredArgsConstructor
public class FacilityServiceImpl implements FacilityService {

    /** 在线状态，与 t_facility.status 的 CHECK 约束一致 */
    private static final String STATUS_ONLINE = "ONLINE";

    /** 离线状态 */
    private static final String STATUS_OFFLINE = "OFFLINE";

    /** 故障状态 */
    private static final String STATUS_FAULT = "FAULT";

    private final FacilityMapper facilityMapper;

    @Override
    public List<FacilityVO> listFacilities(String facilityType, String bbox) {
        String normalizedType = normalizeType(facilityType);
        BboxUtils.Bbox range = BboxUtils.parse(bbox);
        return facilityMapper.selectFacilityList(normalizedType,
                        range.west(), range.south(), range.east(), range.north()).stream()
                .map(FacilityServiceImpl::toVo)
                .collect(Collectors.toList());
    }

    @Override
    public PageResult<FacilityPageVO> pageFacilities(FacilityPageQuery query) {
        Page<FacilityDO> page = new Page<>(query.getCurrent(), query.getSize());
        IPage<FacilityDO> result = facilityMapper.selectFacilityPage(
                page, trimToNull(query.getKeyword()), normalizeType(query.getFacilityType()),
                normalizeStatus(query.getStatus()));
        List<FacilityPageVO> records = result.getRecords().stream()
                .map(FacilityServiceImpl::toPageVo)
                .collect(Collectors.toList());
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private static FacilityVO toVo(FacilityDO facility) {
        FacilityVO vo = new FacilityVO();
        BeanUtils.copyProperties(facility, vo);
        resolveTypeLabel(facility).ifPresent(vo::setFacilityTypeLabel);
        return vo;
    }

    private static FacilityPageVO toPageVo(FacilityDO facility) {
        FacilityPageVO vo = new FacilityPageVO();
        BeanUtils.copyProperties(facility, vo);
        resolveTypeLabel(facility).ifPresent(vo::setFacilityTypeLabel);
        return vo;
    }

    /**
     * 设施类型合法时返回中文名，未知类型返回空交由前端兜底
     */
    private static java.util.Optional<String> resolveTypeLabel(FacilityDO facility) {
        return FacilityTypeEnum.of(facility.getFacilityType())
                .map(FacilityTypeEnum::getLabel);
    }

    /**
     * 校验设施类型并去空，非法时抛出参数错误
     */
    private static String normalizeType(String facilityType) {
        String normalized = trimToNull(facilityType);
        if (normalized != null && FacilityTypeEnum.of(normalized).isEmpty()) {
            String supported = Arrays.stream(FacilityTypeEnum.values())
                    .map(Enum::name)
                    .collect(Collectors.joining("/"));
            throw new BizException(ResultCodeEnum.PARAM_ERROR, "设施类型不支持，可选值：" + supported);
        }
        return normalized;
    }

    /**
     * 校验运行状态并去空，非法时抛出参数错误
     */
    private static String normalizeStatus(String status) {
        String normalized = trimToNull(status);
        if (normalized != null && !STATUS_ONLINE.equals(normalized)
                && !STATUS_OFFLINE.equals(normalized) && !STATUS_FAULT.equals(normalized)) {
            throw new BizException(ResultCodeEnum.PARAM_ERROR, "设施状态不支持，可选值：ONLINE/OFFLINE/FAULT");
        }
        return normalized;
    }

    /**
     * 空白参数统一归一为 null，交给 SQL 的 if 判断
     */
    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
