package com.wuhan.twin.stat.service;

import com.wuhan.twin.stat.vo.StatOverviewVO;

/**
 * 概览统计服务
 *
 * @author lvfan
 */
public interface StatService {

    /**
     * 查询概览指标
     *
     * @return 设备总数、在线数、待处理告警数
     */
    StatOverviewVO getOverview();
}
