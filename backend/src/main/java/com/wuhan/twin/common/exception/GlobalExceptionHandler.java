package com.wuhan.twin.common.exception;

import com.wuhan.twin.common.result.R;
import com.wuhan.twin.common.result.ResultCodeEnum;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理
 *
 * <p>统一返回 HTTP 200，业务结果由 R.code 表达，前端只需判断一处。</p>
 *
 * @author lvfan
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常，属预期分支，不打堆栈
     */
    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e, HttpServletRequest request) {
        log.warn("业务异常 [{} {}]：{}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验失败。MethodArgumentNotValidException 是 BindException 子类，
     * 故 @Valid 修饰的请求体与表单绑定失败均走此处
     */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(GlobalExceptionHandler::formatFieldError)
                .orElse(ResultCodeEnum.PARAM_ERROR.getMsg());
        log.warn("参数校验失败 [{} {}]：{}", request.getMethod(), request.getRequestURI(), msg);
        return R.fail(ResultCodeEnum.PARAM_ERROR.getCode(), msg);
    }

    /**
     * 方法参数上的约束校验失败，如 @RequestParam @Min
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public R<Void> handleConstraintViolationException(ConstraintViolationException e, HttpServletRequest request) {
        String msg = e.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .orElse(ResultCodeEnum.PARAM_ERROR.getMsg());
        log.warn("参数校验失败 [{} {}]：{}", request.getMethod(), request.getRequestURI(), msg);
        return R.fail(ResultCodeEnum.PARAM_ERROR.getCode(), msg);
    }

    /**
     * 缺少必填请求参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<Void> handleMissingParameter(MissingServletRequestParameterException e, HttpServletRequest request) {
        String msg = "缺少必填参数：" + e.getParameterName();
        log.warn("参数缺失 [{} {}]：{}", request.getMethod(), request.getRequestURI(), msg);
        return R.fail(ResultCodeEnum.PARAM_ERROR.getCode(), msg);
    }

    /**
     * 参数类型不匹配，如 id 传了非数字
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public R<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        String msg = "参数类型不正确：" + e.getName();
        log.warn("参数类型错误 [{} {}]：{}", request.getMethod(), request.getRequestURI(), msg);
        return R.fail(ResultCodeEnum.PARAM_ERROR.getCode(), msg);
    }

    /**
     * 请求方法不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public R<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        log.warn("请求方法不支持 [{} {}]：{}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return R.fail(ResultCodeEnum.METHOD_NOT_ALLOWED);
    }

    /**
     * 未映射的请求路径。Spring 6.1 起静态资源找不到会抛此异常，
     * 若不单独处理会落到兜底分支返回 500 并打整条堆栈
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public R<Void> handleNoResourceFound(NoResourceFoundException e, HttpServletRequest request) {
        log.warn("路径不存在 [{} {}]", request.getMethod(), request.getRequestURI());
        return R.fail(ResultCodeEnum.NOT_FOUND);
    }

    /**
     * 兜底处理，对外只给固定文案，内部细节仅落日志
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常 [{} {}]", request.getMethod(), request.getRequestURI(), e);
        return R.fail(ResultCodeEnum.SYSTEM_ERROR);
    }

    private static String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + " " + fieldError.getDefaultMessage();
    }
}
