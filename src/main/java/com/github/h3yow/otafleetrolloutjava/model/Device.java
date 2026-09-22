package com.github.h3yow.otafleetrolloutjava.model;

import java.time.Instant;

/** 设备实体：本地持久化模拟 */
public class Device {
    private final String deviceId;
    private final String platform;
    private volatile String currentVersion;
    /** 最近一次确认可用（升级成功或回滚后确认）的版本 */
    private volatile String lastConfirmedVersion;
    private volatile DeviceStatus status;
    private volatile Instant lastHeartbeatAt;
    /** 当前所属进行中活动，null 表示空闲 */
    private volatile String activeCampaignId;

    public Device(String deviceId, String platform, String initialVersion) {
        this.deviceId = deviceId;
        this.platform = platform;
        this.currentVersion = initialVersion;
        this.lastConfirmedVersion = initialVersion;
        this.status = DeviceStatus.IDLE;
        this.lastHeartbeatAt = Instant.now();
    }

    public String getDeviceId() { return deviceId; }
    public String getPlatform() { return platform; }
    public String getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(String currentVersion) { this.currentVersion = currentVersion; }
    public String getLastConfirmedVersion() { return lastConfirmedVersion; }
    public void setLastConfirmedVersion(String v) { this.lastConfirmedVersion = v; }
    public DeviceStatus getStatus() { return status; }
    public void setStatus(DeviceStatus status) { this.status = status; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Instant t) { this.lastHeartbeatAt = t; }
    public String getActiveCampaignId() { return activeCampaignId; }
    public void setActiveCampaignId(String id) { this.activeCampaignId = id; }
}
