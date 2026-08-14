package com.wuhan.twin.common.result;

import lombok.Getter;

/**
 * 响应码枚举
 *
 * <p>1000 以下沿用 HTTP 语义，1000 及以上为业务自定义码。</p>
 *
 * @author lvfan
 */
@Getter
public enum ResultCodeEnum {

    /**
     * 成功
     */
    SUCCESS(200, "操作成功"),

    /**
     * 参数不合法
     */
    PARAM_ERROR(400, "参数校验失败"),

    /**
     * 资源不存在
     */
    NOT_FOUND(404, "资源不存在"),

    /**
     * 请求方法不支持
     */
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),

    /**
     * 未捕获的系统异常，对外不暴露内部细节
     */
    SYSTEM_ERROR(500, "系统内部错误，请联系管理员"),

    /**
     * 业务处理失败，BizException 默认码
     */
    BIZ_ERROR(1000, "业务处理失败");

    /**
     * 响应码
     */
    private final int code;

    /**
     * 提示信息
     */
    private final String msg;

    ResultCodeEnum(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
