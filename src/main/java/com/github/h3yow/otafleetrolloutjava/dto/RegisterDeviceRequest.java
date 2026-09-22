package com.github.h3yow.otafleetrolloutjava.dto;

public record RegisterDeviceRequest(String deviceId, String platform, String initialVersion) {
}
