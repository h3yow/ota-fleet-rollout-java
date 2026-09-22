package com.github.h3yow.otafleetrolloutjava.model;

/** 设备生命周期状态 */
public enum DeviceStatus {
    /** 已注册，空闲 */
    IDLE,
    /** 正在某升级活动中升级 */
    UPGRADING,
    /** 升级失败，等待回滚 */
    FAILED,
    /** 已回滚到最近确认可用版本 */
    ROLLED_BACK
}
