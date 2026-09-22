package com.github.h3yow.otafleetrolloutjava.exception;

import com.github.h3yow.otafleetrolloutjava.model.RejectReason;

/** 资源不存在：本质也是一类可区分拒绝 */
public class NotFoundException extends RejectedException {
    public NotFoundException(RejectReason reason, String message) {
        super(reason, message);
    }
}
