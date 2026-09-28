package com.github.h3yow.otafleetrolloutjava.dto.rehearsal;

import java.util.List;

/**
 * 上线前彩排报告：不落库、无任何真实变更。
 * feasible=false 时必须保证未创建活动、未占用设备、未产生升级记录。
 */
public record RehearsalReport(String campaignId, String targetFirmwareVersion,
                              boolean feasible, int totalPlannedDevices,
                              int totalCoveredDevices, int totalBlockedDevices,
                              List<BatchPlan> batches) {
}
