package com.wuhan.twin.common.exception;

import com.wuhan.twin.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常，由 GlobalExceptionHandler 统一转成 R
 *
 * <p>不填充堆栈：业务异常属预期分支，堆栈无诊断价值且有性能开销。</p>
 *
 * @author lvfan
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 响应码
     */
    private final int code;

    /**
     * 业务默认码 + 自定义提示
     */
    public BizException(String msg) {
        this(ResultCode.BIZ_ERROR.getCode(), msg);
    }

    /**
     * 使用枚举自带的码与提示
     */
    public BizException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMsg());
    }

    /**
     * 枚举的码 + 自定义提示
     */
    public BizException(ResultCode resultCode, String msg) {
        this(resultCode.getCode(), msg);
    }

    public BizException(int code, String msg) {
        super(msg, null, false, false);
        this.code = code;
    }
}
