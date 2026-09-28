package com.github.h3yow.otafleetrolloutjava.model;

/** 升级活动状态 */
public enum CampaignStatus {
    /** 已创建，尚未下发 */
    CREATED,
    /** 进行中 */
    RUNNING,
    /** 已暂停（手动或失败率超阈值自动暂停） */
    PAUSED,
    /** 首批结清后等待人工确认，确认前不下发后续批次 */
    AWAITING_CONFIRMATION,
    /** 已完成全部批次 */
    COMPLETED,
    /** 已终止 */
    TERMINATED
}
