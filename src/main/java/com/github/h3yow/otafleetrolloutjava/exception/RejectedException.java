package com.github.h3yow.otafleetrolloutjava.exception;

import com.github.h3yow.otafleetrolloutjava.model.RejectReason;

/** 业务拒绝异常：携带可区分的原因码，供接口与测试断言 */
public class RejectedException extends RuntimeException {
    private final RejectReason reason;

    public RejectedException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
