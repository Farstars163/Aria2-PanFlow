package com.farstars.common;

/**
 * 统一 API 响应结构
 */
public record Result(int code, String msg, Object data) {

    public static Result ok() {
        return new Result(200, "success", null);
    }

    public static Result ok(Object data) {
        return new Result(200, "success", data);
    }

    public static Result error(String msg) {
        return new Result(500, msg, null);
    }

    public static Result error(int code, String msg) {
        return new Result(code, msg, null);
    }
}
