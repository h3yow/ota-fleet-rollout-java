package com.github.h3yow.otafleetrolloutjava.dto;

import com.github.h3yow.otafleetrolloutjava.model.TaskStatus;

public class TaskView {
    private String campaignId;
    private int batchIndex;
    private String deviceId;
    private String targetVersion;
    private TaskStatus status;
    private int attempts;
    private long lastReportSeq;
    private String reportedVersion;
    private String rollbackToVersion;
    private String failureReason;

    public String getCampaignId() { return campaignId; }
    public void setCampaignId(String campaignId) { this.campaignId = campaignId; }
    public int getBatchIndex() { return batchIndex; }
    public void setBatchIndex(int batchIndex) { this.batchIndex = batchIndex; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getTargetVersion() { return targetVersion; }
    public void setTargetVersion(String targetVersion) { this.targetVersion = targetVersion; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public long getLastReportSeq() { return lastReportSeq; }
    public void setLastReportSeq(long lastReportSeq) { this.lastReportSeq = lastReportSeq; }
    public String getReportedVersion() { return reportedVersion; }
    public void setReportedVersion(String reportedVersion) { this.reportedVersion = reportedVersion; }
    public String getRollbackToVersion() { return rollbackToVersion; }
    public void setRollbackToVersion(String rollbackToVersion) { this.rollbackToVersion = rollbackToVersion; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}
