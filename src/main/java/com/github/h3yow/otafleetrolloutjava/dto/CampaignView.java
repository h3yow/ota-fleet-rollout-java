package com.github.h3yow.otafleetrolloutjava.dto;

import com.github.h3yow.otafleetrolloutjava.model.CampaignStatus;

import java.util.List;

public class CampaignView {
    private String campaignId;
    private String firmwareId;
    private String targetVersion;
    private String platform;
    private double failureRateThreshold;
    private CampaignStatus status;
    private int activeBatchIndex;
    private String pauseReason;
    private List<BatchView> batches;
    private CampaignStats stats;

    public String getCampaignId() { return campaignId; }
    public void setCampaignId(String campaignId) { this.campaignId = campaignId; }
    public String getFirmwareId() { return firmwareId; }
    public void setFirmwareId(String firmwareId) { this.firmwareId = firmwareId; }
    public String getTargetVersion() { return targetVersion; }
    public void setTargetVersion(String targetVersion) { this.targetVersion = targetVersion; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public double getFailureRateThreshold() { return failureRateThreshold; }
    public void setFailureRateThreshold(double failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }
    public CampaignStatus getStatus() { return status; }
    public void setStatus(CampaignStatus status) { this.status = status; }
    public int getActiveBatchIndex() { return activeBatchIndex; }
    public void setActiveBatchIndex(int activeBatchIndex) { this.activeBatchIndex = activeBatchIndex; }
    public String getPauseReason() { return pauseReason; }
    public void setPauseReason(String pauseReason) { this.pauseReason = pauseReason; }
    public List<BatchView> getBatches() { return batches; }
    public void setBatches(List<BatchView> batches) { this.batches = batches; }
    public CampaignStats getStats() { return stats; }
    public void setStats(CampaignStats stats) { this.stats = stats; }
}
