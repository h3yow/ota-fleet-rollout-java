package com.github.h3yow.otafleetrolloutjava.controller;

import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 将拒绝原因以可区分形式返回给调用方 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RejectedException.class)
    public ResponseEntity<Map<String, String>> handleRejected(RejectedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("reason", ex.getReason().name(), "message", ex.getMessage()));
    }
}
