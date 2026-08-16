package com.wuhan.twin.ws.vo;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * WebSocket 推送体，格式与 docs 需求说明第 4 章约定一致
 *
 * @author lvfan
 */
@Data
@AllArgsConstructor
public class PushMessageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 设备实时快照批量推送
     */
    public static final String TYPE_DEVICE_UPDATE = "DEVICE_UPDATE";

    /**
     * 市政设施实时快照批量推送。与设备分开推送，前端两个 store 直接分发，无需在回调里过滤
     */
    public static final String TYPE_FACILITY_UPDATE = "FACILITY_UPDATE";

    /**
     * 单条新告警推送
     */
    public static final String TYPE_ALARM_NEW = "ALARM_NEW";

    /**
     * 消息类型
     */
    private String type;

    /**
     * 消息体，DEVICE_UPDATE 与 FACILITY_UPDATE 为数组，ALARM_NEW 为对象
     */
    private Object data;
}
