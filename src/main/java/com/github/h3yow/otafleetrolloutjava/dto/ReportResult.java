package com.github.h3yow.otafleetrolloutjava.dto;

import com.github.h3yow.otafleetrolloutjava.model.TaskStatus;

/** 上报处理结论：accepted=false 时给出可区分原因（重复/乱序/不兼容等） */
public class ReportResult {
    private boolean accepted;
    private String rejectReason;
    private TaskStatus taskStatus;
    private int attempts;
    private boolean campaignAutoPaused;
    private String rollbackToVersion;

    public boolean isAccepted() { return accepted; }
    public void setAccepted(boolean accepted) { this.accepted = accepted; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public TaskStatus getTaskStatus() { return taskStatus; }
    public void setTaskStatus(TaskStatus taskStatus) { this.taskStatus = taskStatus; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public boolean isCampaignAutoPaused() { return campaignAutoPaused; }
    public void setCampaignAutoPaused(boolean campaignAutoPaused) { this.campaignAutoPaused = campaignAutoPaused; }
    public String getRollbackToVersion() { return rollbackToVersion; }
    public void setRollbackToVersion(String rollbackToVersion) { this.rollbackToVersion = rollbackToVersion; }
}
