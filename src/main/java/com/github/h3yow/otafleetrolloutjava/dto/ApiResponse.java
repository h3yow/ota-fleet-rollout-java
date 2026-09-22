package com.github.h3yow.otafleetrolloutjava.dto;

/** 统一返回包装 */
public class ApiResponse<T> {
    private boolean ok;
    private T data;
    private String code;
    private String message;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.ok = true;
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.ok = false;
        r.code = code;
        r.message = message;
        return r;
    }

    public boolean isOk() { return ok; }
    public void setOk(boolean ok) { this.ok = ok; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
