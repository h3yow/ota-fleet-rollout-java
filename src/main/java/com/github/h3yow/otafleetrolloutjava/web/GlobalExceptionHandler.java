package com.github.h3yow.otafleetrolloutjava.web;

import com.github.h3yow.otafleetrolloutjava.dto.ApiResponse;
import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RejectedException.class)
    public ResponseEntity<ApiResponse<Void>> handleRejected(RejectedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getReason().name(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegal(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("INVALID_REQUEST", ex.getMessage()));
    }
}
