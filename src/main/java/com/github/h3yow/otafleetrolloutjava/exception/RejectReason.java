package com.github.h3yow.otafleetrolloutjava.exception;

/** 操作被拒绝的可区分原因 */
public enum RejectReason {
    DEVICE_NOT_FOUND,
    FIRMWARE_NOT_FOUND,
    CAMPAIGN_NOT_FOUND,
    /** 上报缺失版本或平台信息 */
    MISSING_VERSION_OR_PLATFORM,
    /** 上报的平台与设备注册平台不一致 */
    PLATFORM_MISMATCH,
    /** 目标固件与设备平台不兼容 */
    INCOMPATIBLE_FIRMWARE,
    /** 目标固件不满足最低版本兼容约束 */
    VERSION_CONSTRAINT_VIOLATED,
    /** 设备已属于另一个进行中的活动 */
    DEVICE_BUSY_IN_OTHER_CAMPAIGN,
    /** 设备当前状态不允许该操作 */
    ILLEGAL_DEVICE_STATE,
    /** 活动当前状态不允许该操作 */
    ILLEGAL_CAMPAIGN_STATE,
    /** 上报序号乱序/迟到，已被更新数据覆盖 */
    STALE_REPORT,
    /** 同一设备在同一份活动计划（可能跨批次）中重复出现 */
    DUPLICATE_DEVICE_IN_PLAN,
    /** 上一批尚有设备没有明确结论（未上报），不允许推进下一批 */
    BATCH_NOT_SETTLED,
    /** 严格验收下，设备报告成功但实际版本与目标固件不一致 */
    TARGET_VERSION_MISMATCH,
    /** 无可回滚的历史版本 */
    NO_ROLLBACK_TARGET
}
