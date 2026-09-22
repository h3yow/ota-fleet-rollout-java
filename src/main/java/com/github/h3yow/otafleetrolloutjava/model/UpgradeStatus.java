package com.github.h3yow.otafleetrolloutjava.model;

/** 单设备在某活动中的升级状态 */
public enum UpgradeStatus {
    /** 已下发，等待设备上报 */
    DISPATCHED,
    /** 设备上报升级成功 */
    SUCCEEDED,
    /** 设备上报升级失败 */
    FAILED,
    /** 已回滚到最近确认可用版本 */
    ROLLED_BACK
}
