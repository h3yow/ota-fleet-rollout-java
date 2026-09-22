package com.github.h3yow.otafleetrolloutjava.model;

/** 升级活动生命周期状态 */
public enum CampaignStatus {
    PENDING,      // 已创建，尚未激活任何批次
    RUNNING,      // 有批次正在下发/等待上报
    PAUSED,       // 手动暂停或失败率超阈值自动暂停
    COMPLETED,    // 所有批次结束
    TERMINATED    // 人工终止
}
