package com.github.h3yow.otafleetrolloutjava.dto.rehearsal;

/** 彩排中被阻塞的单台设备及其可区分原因 */
public record BlockedDevice(String deviceId, String reason, String message) {
}
