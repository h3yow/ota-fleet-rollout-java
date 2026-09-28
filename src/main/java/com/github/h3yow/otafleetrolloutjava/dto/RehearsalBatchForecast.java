package com.github.h3yow.otafleetrolloutjava.dto;

import java.util.List;

/**
 * 单个批次的彩排预测。
 *
 * @param batchIndex      批次序号（0 起）
 * @param plannedCount    计划内设备数（含重复出现）
 * @param coveredCount    预计可成功下发覆盖的设备数
 * @param blockedDevices  被阻塞设备及各自原因
 */
public record RehearsalBatchForecast(int batchIndex, int plannedCount, int coveredCount,
                                     List<RehearsalBlockedDevice> blockedDevices) {
}
