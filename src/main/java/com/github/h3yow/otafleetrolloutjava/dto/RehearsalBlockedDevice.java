package com.github.h3yow.otafleetrolloutjava.dto;

/** 彩排中被阻塞的设备及其原因 */
public record RehearsalBlockedDevice(String deviceId, String reason, String detail) {
}
