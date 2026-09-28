package com.github.h3yow.otafleetrolloutjava.dto;

import java.util.List;

/**
 * 上线前彩排结论。彩排为纯只读评估，不产生任何真实变更。
 *
 * @param executable  整条计划是否可以无阻塞执行
 * @param targetFirmwareVersion 目标固件版本
 * @param batches     按批次的覆盖预测
 */
public record RehearsalResult(boolean executable, String targetFirmwareVersion,
                              List<RehearsalBatchForecast> batches) {
}
