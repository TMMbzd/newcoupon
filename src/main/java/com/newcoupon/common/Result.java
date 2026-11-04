package com.newcoupon.common;

/**
 * 统一响应体。所有 REST 接口一律返回 {@code Result<T>}，禁止裸 Map / 裸实体直出。
 *
 * <p>为什么？——对齐 docs/01 第 4.8 节接口契约：调用方拿到的是
 * {@code code + message + data} 三段结构。code 是程序分支的依据（见 {@link ErrorCode}），
 * message 是给用户的文案，data 是业务数据。响应形状不统一，上游就要为每个接口写一套解析，
 * 这在多业务方接入的中台里是灾难。
 *
 * @param <T> 业务数据类型
 */
public final class Result<T> {

    private final int code;
    private final String message;
    private final T data;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success(T data) {
        // S1 审计：把响应码记进请求上下文——开放面的调用审计要记"这次调用最终是什么结果"，
        // 而响应体在过滤器阶段已经提交、读不到（见设计文档 §9-4），所以在源头记一笔。
        // 非 HTTP 线程里 current() 返回 EMPTY，写入自动 no-op。
        com.newcoupon.common.context.RequestContext.current().resultCode(ErrorCode.SUCCESS.getCode());
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static Result<Void> success() {
        return success(null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        com.newcoupon.common.context.RequestContext.current().resultCode(errorCode.getCode());
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String detail) {
        com.newcoupon.common.context.RequestContext.current().resultCode(errorCode.getCode());
        // detail 是补充信息（如"券当前状态=已核销"），拼接在标准 message 之后
        return new Result<>(errorCode.getCode(), errorCode.getMessage() + ": " + detail, null);
    }

    /**
     * 带结构化 data 的失败（V6 2B-2）：错误本体不变，但 data 携带**程序化可读的引导信息**——
     * 首个使用方是 6010 的 {@code data.supportedVersions}（告诉接入方当前有哪些版本可用）。
     *
     * <p>与 {@link #fail(ErrorCode, String)} 的分工：那个的 detail 拼进 message，是给人看的补充文案；
     * 这个的 data 原样放进 data 段，是给调用方代码分支用的结构化字段。
     * 传 String 实参时仍优先绑定 detail 版本（更具体的重载），不受本方法影响。
     */
    public static <T> Result<T> fail(ErrorCode errorCode, T data) {
        com.newcoupon.common.context.RequestContext.current().resultCode(errorCode.getCode());
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), data);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}
