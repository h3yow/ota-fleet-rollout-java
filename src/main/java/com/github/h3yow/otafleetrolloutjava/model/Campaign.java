package com.github.h3yow.otafleetrolloutjava.model;

import java.util.ArrayList;
import java.util.List;

/** 分批灰度升级活动 */
public class Campaign {
    private final String campaignId;
    private final String targetFirmwareVersion;
    /** 批次：每批一组 deviceId */
    private final List<List<String>> batches;
    /** 失败率阈值（百分比 0-100），超过则自动暂停 */
    private final double failureThresholdPercent;
    private volatile CampaignStatus status;
    private volatile int currentBatchIndex;
    /** 是否因失败率超阈值而自动暂停 */
    private volatile boolean autoPaused;

    public Campaign(String campaignId, String targetFirmwareVersion,
                    List<List<String>> batches, double failureThresholdPercent) {
        this.campaignId = campaignId;
        this.targetFirmwareVersion = targetFirmwareVersion;
        this.batches = new ArrayList<>(batches);
        this.failureThresholdPercent = failureThresholdPercent;
        this.status = CampaignStatus.CREATED;
        this.currentBatchIndex = -1;
    }

    public String getCampaignId() { return campaignId; }
    public String getTargetFirmwareVersion() { return targetFirmwareVersion; }
    public List<List<String>> getBatches() { return batches; }
    public double getFailureThresholdPercent() { return failureThresholdPercent; }
    public CampaignStatus getStatus() { return status; }
    public void setStatus(CampaignStatus status) { this.status = status; }
    public int getCurrentBatchIndex() { return currentBatchIndex; }
    public void setCurrentBatchIndex(int i) { this.currentBatchIndex = i; }
    public boolean isAutoPaused() { return autoPaused; }
    public void setAutoPaused(boolean autoPaused) { this.autoPaused = autoPaused; }
}
