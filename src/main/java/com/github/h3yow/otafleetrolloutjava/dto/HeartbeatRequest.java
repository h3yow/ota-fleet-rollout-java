package com.github.h3yow.otafleetrolloutjava.dto;

public class HeartbeatRequest {
    private String deviceId;
    private String platform;
    private String currentVersion;

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(String currentVersion) { this.currentVersion = currentVersion; }
}
