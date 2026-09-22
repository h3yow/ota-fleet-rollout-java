package com.github.h3yow.otafleetrolloutjava.exception;

/** 业务操作被拒绝时抛出，携带可区分的拒绝原因 */
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
