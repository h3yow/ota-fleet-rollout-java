package com.github.h3yow.otafleetrolloutjava.dto;

import com.github.h3yow.otafleetrolloutjava.model.ReportOutcome;

/** 设备升级结果上报；seq 单调递增，用于乱序/迟到判定 */
public class UpgradeReportRequest {
    private String deviceId;
    private String campaignId;
    private Long seq;
    private ReportOutcome outcome;
    private String reportedVersion;  // 上报时设备实际运行版本
    private String platform;

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getCampaignId() { return campaignId; }
    public void setCampaignId(String campaignId) { this.campaignId = campaignId; }
    public Long getSeq() { return seq; }
    public void setSeq(Long seq) { this.seq = seq; }
    public ReportOutcome getOutcome() { return outcome; }
    public void setOutcome(ReportOutcome outcome) { this.outcome = outcome; }
    public String getReportedVersion() { return reportedVersion; }
    public void setReportedVersion(String reportedVersion) { this.reportedVersion = reportedVersion; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
}
