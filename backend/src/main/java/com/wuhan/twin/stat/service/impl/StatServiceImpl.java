package com.wuhan.twin.stat.service.impl;

import com.wuhan.twin.stat.entity.StatOverviewDO;
import com.wuhan.twin.stat.mapper.StatMapper;
import com.wuhan.twin.stat.service.StatService;
import com.wuhan.twin.stat.vo.StatOverviewVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

/**
 * 概览统计服务实现
 *
 * @author lvfan
 */
@Service
@RequiredArgsConstructor
public class StatServiceImpl implements StatService {

    /**
     * 各项计数的空值兜底
     */
    private static final long EMPTY_COUNT = 0L;

    private final StatMapper statMapper;

    @Override
    public StatOverviewVO getOverview() {
        StatOverviewDO overview = statMapper.selectOverview();
        StatOverviewVO vo = new StatOverviewVO();
        if (overview == null) {
            vo.setDeviceTotal(EMPTY_COUNT);
            vo.setOnlineCount(EMPTY_COUNT);
            vo.setAlarmCount(EMPTY_COUNT);
            return vo;
        }
        BeanUtils.copyProperties(overview, vo);
        return vo;
    }
}
