package com.github.h3yow.otafleetrolloutjava.dto;

public record HeartbeatRequest(String deviceId, String currentVersion, String platform) {
}
