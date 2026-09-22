package com.github.h3yow.otafleetrolloutjava.model;

/** 批次状态 */
public enum BatchStatus {
    PENDING,      // 尚未激活，未下发
    ACTIVE,       // 已下发，等待设备上报
    COMPLETED     // 批次内设备均已终态
}
