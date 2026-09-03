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
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static Result<Void> success() {
        return success(null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String detail) {
        // detail 是补充信息（如"券当前状态=已核销"），拼接在标准 message 之后
        return new Result<>(errorCode.getCode(), errorCode.getMessage() + ": " + detail, null);
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
