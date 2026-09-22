package com.github.h3yow.otafleetrolloutjava.model;

/** 单设备升级任务状态（状态机只允许前进，不允许倒退） */
public enum TaskStatus {
    ASSIGNED,     // 已随批次分配但批次未激活
    DISPATCHED,   // 批次激活，指令已下发
    SUCCESS,      // 上报成功且校验通过
    ROLLED_BACK,  // 上报失败/校验不通过，已回滚到最近确认可用版本
    FAILED        // 失败且无可用回滚基线
}
