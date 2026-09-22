package com.github.h3yow.otafleetrolloutjava.model;

import java.time.Instant;

/** 单设备在某活动中的升级记录（用于幂等计数与状态机） */
public class UpgradeRecord {
    private final String deviceId;
    private final String campaignId;
    private final String fromVersion;
    private final String toVersion;
    private volatile UpgradeStatus status;
    /** 已应用的最大上报序号，用于乱序/迟到上报去重 */
    private volatile long lastAppliedSequence;
    private volatile Instant updatedAt;

    public UpgradeRecord(String deviceId, String campaignId, String fromVersion, String toVersion) {
        this.deviceId = deviceId;
        this.campaignId = campaignId;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.status = UpgradeStatus.DISPATCHED;
        this.lastAppliedSequence = -1;
        this.updatedAt = Instant.now();
    }

    public String getDeviceId() { return deviceId; }
    public String getCampaignId() { return campaignId; }
    public String getFromVersion() { return fromVersion; }
    public String getToVersion() { return toVersion; }
    public UpgradeStatus getStatus() { return status; }
    public void setStatus(UpgradeStatus status) { this.status = status; }
    public long getLastAppliedSequence() { return lastAppliedSequence; }
    public void setLastAppliedSequence(long seq) { this.lastAppliedSequence = seq; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant t) { this.updatedAt = t; }
}
