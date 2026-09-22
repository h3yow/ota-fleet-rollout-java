package com.github.h3yow.otafleetrolloutjava.dto;

/**
 * 升级结果上报。
 * sequence 为设备侧单调递增序号，用于乱序/迟到去重。
 */
public record ReportUpgradeRequest(String deviceId, String campaignId, long sequence,
                                   boolean success, String reportedVersion, String platform) {
}
