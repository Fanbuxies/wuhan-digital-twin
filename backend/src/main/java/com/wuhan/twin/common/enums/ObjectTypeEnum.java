package com.wuhan.twin.common.enums;

import lombok.Getter;

/**
 * 监测对象类型，取值与 t_device_realtime / t_device_telemetry / t_alarm 的 object_type 约束一致
 *
 * <p>楼内设备与室外市政设施共用同一套实时、历史与告警表。两张台账表的主键都是从 1 开始
 * 的 bigserial，仅凭 device_id 无法区分归属，故所有读写都必须带上本枚举。</p>
 *
 * @author lvfan
 */
@Getter
public enum ObjectTypeEnum {

    /**
     * 楼内物联网设备，device_id 指向 t_device.id
     */
    DEVICE("楼内设备"),

    /**
     * 室外市政设施，device_id 指向 t_facility.id
     */
    FACILITY("市政设施");

    /**
     * 中文名称
     */
    private final String label;

    ObjectTypeEnum(String label) {
        this.label = label;
    }
}
