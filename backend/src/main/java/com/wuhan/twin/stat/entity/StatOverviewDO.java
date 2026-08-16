package com.wuhan.twin.stat.entity;

import java.io.Serializable;

import lombok.Data;

/**
 * 概览统计查询结果载体
 *
 * <p>不对应任何表，仅承载一条聚合 SQL 的各项计数，避免用 Map 接收结果集。</p>
 *
 * @author lvfan
 */
@Data
public class StatOverviewDO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 设备总数
     */
    private Long deviceTotal;

    /**
     * 在线设备数
     */
    private Long onlineCount;

    /**
     * 设备待处理告警数
     */
    private Long alarmCount;

    /**
     * 市政设施总数
     */
    private Long facilityTotal;

    /**
     * 在线市政设施数
     */
    private Long facilityOnlineCount;

    /**
     * 市政设施待处理告警数
     */
    private Long facilityAlarmCount;
}
