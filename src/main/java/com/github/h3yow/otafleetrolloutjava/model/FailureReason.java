package com.github.h3yow.otafleetrolloutjava.model;

/** 升级记录失败的可区分原因（用于明确失败结论） */
public enum FailureReason {
    /** 设备主动上报失败 */
    DEVICE_REPORTED_FAILURE,
    /** 受控发布：上报成功但实际版本与目标固件不一致 */
    REPORTED_VERSION_MISMATCH
}
