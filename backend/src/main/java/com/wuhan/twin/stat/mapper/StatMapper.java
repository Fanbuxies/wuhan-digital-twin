package com.wuhan.twin.stat.mapper;

import com.wuhan.twin.stat.entity.StatOverviewDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 概览统计 mapper
 *
 * @author lvfan
 */
@Mapper
public interface StatMapper {

    /**
     * 一条 SQL 取回设备总数、在线数与待处理告警数
     *
     * @return 统计结果，各计数无数据时为 0
     */
    StatOverviewDO selectOverview();
}
