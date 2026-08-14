package com.wuhan.twin.common.result;

import java.io.Serializable;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应包装，Controller 一律返回该类型
 *
 * @param <T> 业务数据类型
 * @author lvfan
 */
@Data
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 响应码，200 表示成功
     */
    private Integer code;

    /**
     * 提示信息
     */
    private String msg;

    /**
     * 业务数据，失败时为 null
     */
    private T data;

    /**
     * 成功，无返回数据
     */
    public static <T> R<T> ok() {
        return ok(null);
    }

    /**
     * 成功，携带业务数据
     */
    public static <T> R<T> ok(T data) {
        return new R<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), data);
    }

    /**
     * 失败，使用枚举自带的码与提示
     */
    public static <T> R<T> fail(ResultCode resultCode) {
        return new R<>(resultCode.getCode(), resultCode.getMsg(), null);
    }

    /**
     * 失败，业务默认码 + 自定义提示
     */
    public static <T> R<T> fail(String msg) {
        return new R<>(ResultCode.BIZ_ERROR.getCode(), msg, null);
    }

    /**
     * 失败，完全自定义码与提示
     */
    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }
}
