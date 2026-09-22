package com.github.h3yow.otafleetrolloutjava.model;

import java.util.concurrent.atomic.AtomicLong;

/** 设备：当前版本 + 最近一次确认可用版本（回滚基线） */
public class Device {
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String deviceId;
    private String platform;
    private String currentVersion;
    private String lastKnownGoodVersion;
    private long lastHeartbeatAt;
    private long registeredAt;

    public Device() {
    }

    public static Device register(String deviceId, String platform, String currentVersion, long now) {
        Device d = new Device();
        d.deviceId = deviceId;
        d.platform = platform;
        d.currentVersion = currentVersion;
        d.lastKnownGoodVersion = currentVersion; // 出厂版本即首个确认可用版本
        d.registeredAt = now;
        d.lastHeartbeatAt = now;
        return d;
    }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(String currentVersion) { this.currentVersion = currentVersion; }
    public String getLastKnownGoodVersion() { return lastKnownGoodVersion; }
    public void setLastKnownGoodVersion(String v) { this.lastKnownGoodVersion = v; }
    public long getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(long t) { this.lastHeartbeatAt = t; }
    public long getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(long t) { this.registeredAt = t; }

    public static long nextSeq() { return SEQ.incrementAndGet(); }
}
