package com.github.h3yow.otafleetrolloutjava.model;

import java.util.ArrayList;
import java.util.List;

/** 分批灰度升级活动 */
public class RolloutCampaign {
    private String campaignId;
    private String firmwareId;
    private String targetVersion;
    private String platform;
    private double failureRateThreshold; // 0~1，失败率达到/超过则自动暂停
    private CampaignStatus status = CampaignStatus.PENDING;
    private List<CampaignBatch> batches = new ArrayList<>();
    private int activeBatchIndex = -1;
    private long createdAt;
    private long updatedAt;
    private String pauseReason; // MANUAL / AUTO_FAILURE_RATE / null

    public RolloutCampaign() {
    }

    /** 当前批次是否全部到达终态 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isActiveBatchSettled(java.util.Map<String, UpgradeTask> taskIndex) {
        if (activeBatchIndex < 0 || activeBatchIndex >= batches.size()) {
            return true;
        }
        for (String deviceId : batches.get(activeBatchIndex).getDeviceIds()) {
            UpgradeTask t = taskIndex.get(campaignId + ":" + deviceId);
            if (t == null || !t.isTerminal()) {
                return false;
            }
        }
        return true;
    }

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
    public List<CampaignBatch> getBatches() { return batches; }
    public void setBatches(List<CampaignBatch> batches) { this.batches = batches; }
    public int getActiveBatchIndex() { return activeBatchIndex; }
    public void setActiveBatchIndex(int activeBatchIndex) { this.activeBatchIndex = activeBatchIndex; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public String getPauseReason() { return pauseReason; }
    public void setPauseReason(String pauseReason) { this.pauseReason = pauseReason; }
}
