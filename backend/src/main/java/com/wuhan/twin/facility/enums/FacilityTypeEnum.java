package com.wuhan.twin.facility.enums;

import java.util.Arrays;
import java.util.Optional;

import lombok.Getter;

/**
 * 市政设施类型，取值与 t_facility.facility_type 的 CHECK 约束保持一致
 *
 * @author lvfan
 */
@Getter
public enum FacilityTypeEnum {

    /**
     * 充电桩
     */
    CHARGING_PILE("充电桩"),

    /**
     * 路灯
     */
    STREET_LAMP("路灯"),

    /**
     * 井盖
     */
    MANHOLE("井盖"),

    /**
     * 公交站
     */
    BUS_STOP("公交站");

    /**
     * 中文名称
     */
    private final String label;

    FacilityTypeEnum(String label) {
        this.label = label;
    }

    /**
     * 按名称匹配枚举
     *
     * @param name 类型名称，大小写敏感
     * @return 匹配不到返回 Optional.empty()
     */
    public static Optional<FacilityTypeEnum> of(String name) {
        return Arrays.stream(values())
                .filter(item -> item.name().equals(name))
                .findFirst();
    }
}
