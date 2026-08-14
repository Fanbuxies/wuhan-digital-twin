package com.wuhan.twin.device.enums;

import java.util.Arrays;
import java.util.Optional;

import lombok.Getter;

/**
 * 设备类型，取值与 t_device.device_type 的 CHECK 约束保持一致
 *
 * @author lvfan
 */
@Getter
public enum DeviceTypeEnum {

    /**
     * 烟感
     */
    SMOKE("烟感"),

    /**
     * 水浸
     */
    WATER("水浸"),

    /**
     * 温湿度
     */
    TEMP_HUMI("温湿度"),

    /**
     * 电气
     */
    ELECTRIC("电气"),

    /**
     * 摄像头
     */
    CAMERA("摄像头");

    /**
     * 中文名称
     */
    private final String label;

    DeviceTypeEnum(String label) {
        this.label = label;
    }

    /**
     * 按名称匹配枚举
     *
     * @param name 类型名称，大小写敏感
     * @return 匹配不到返回 Optional.empty()
     */
    public static Optional<DeviceTypeEnum> of(String name) {
        return Arrays.stream(values())
                .filter(item -> item.name().equals(name))
                .findFirst();
    }
}
