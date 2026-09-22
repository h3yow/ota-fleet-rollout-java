package com.github.h3yow.otafleetrolloutjava.model;

import java.util.List;

/** 固件包：平台、版本与兼容约束（最低基线版本 + 允许升级的来源平台） */
public class Firmware {
    private String firmwareId;
    private String platform;
    private String version;
    private String minBaseVersion;          // 设备当前版本必须 >= 该版本才可下发
    private List<String> compatiblePlatforms; // 为空表示仅允许同平台
    private long createdAt;

    public Firmware() {
    }

    public String getFirmwareId() { return firmwareId; }
    public void setFirmwareId(String firmwareId) { this.firmwareId = firmwareId; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getMinBaseVersion() { return minBaseVersion; }
    public void setMinBaseVersion(String minBaseVersion) { this.minBaseVersion = minBaseVersion; }
    public List<String> getCompatiblePlatforms() { return compatiblePlatforms; }
    public void setCompatiblePlatforms(List<String> compatiblePlatforms) { this.compatiblePlatforms = compatiblePlatforms; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
