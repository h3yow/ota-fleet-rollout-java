package com.github.h3yow.otafleetrolloutjava.model;

/**
 * 单设备升级任务。每个 (campaign, device) 唯一。
 * sequence 为设备上报携带的序列号，用于识别乱序/迟到上报：
 * 只有 seq > 已处理 seq 的上报才会被接受并推进状态。
 */
public class UpgradeTask {
    private String campaignId;
    private int batchIndex;
    private String deviceId;
    private String targetVersion;
    private TaskStatus status;
    private long dispatchedAt;
    private long completedAt;
    private long lastReportSeq;   // 最近一次已接受上报的序列号
    private String reportedVersion;
    private String rollbackToVersion;
    private String failureReason;
    private int attempts;         // 实际升级尝试次数（重复上报不增加）

    public UpgradeTask() {
    }

    public UpgradeTask(String campaignId, int batchIndex, String deviceId, String targetVersion) {
        this.campaignId = campaignId;
        this.batchIndex = batchIndex;
        this.deviceId = deviceId;
        this.targetVersion = targetVersion;
        this.status = TaskStatus.ASSIGNED;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isTerminal() {
        return status == TaskStatus.SUCCESS || status == TaskStatus.ROLLED_BACK || status == TaskStatus.FAILED;
    }

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
    public long getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(long dispatchedAt) { this.dispatchedAt = dispatchedAt; }
    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }
    public long getLastReportSeq() { return lastReportSeq; }
    public void setLastReportSeq(long lastReportSeq) { this.lastReportSeq = lastReportSeq; }
    public String getReportedVersion() { return reportedVersion; }
    public void setReportedVersion(String reportedVersion) { this.reportedVersion = reportedVersion; }
    public String getRollbackToVersion() { return rollbackToVersion; }
    public void setRollbackToVersion(String rollbackToVersion) { this.rollbackToVersion = rollbackToVersion; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
}
