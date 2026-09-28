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
    /** 受控发布：设备必须实际安装到目标固件版本才算该批成功 */
    private final boolean controlled;
    /** 是否在首批结清后停在“等待确认”状态，确认前不下发后续批次 */
    private final boolean requireConfirmationAfterFirstBatch;
    private volatile CampaignStatus status;
    private volatile int currentBatchIndex;
    /** 是否因失败率超阈值而自动暂停 */
    private volatile boolean autoPaused;

    public Campaign(String campaignId, String targetFirmwareVersion,
                    List<List<String>> batches, double failureThresholdPercent) {
        this(campaignId, targetFirmwareVersion, batches, failureThresholdPercent, false, false);
    }

    public Campaign(String campaignId, String targetFirmwareVersion,
                    List<List<String>> batches, double failureThresholdPercent,
                    boolean controlled, boolean requireConfirmationAfterFirstBatch) {
        this.campaignId = campaignId;
        this.targetFirmwareVersion = targetFirmwareVersion;
        this.batches = new ArrayList<>(batches);
        this.failureThresholdPercent = failureThresholdPercent;
        this.controlled = controlled;
        this.requireConfirmationAfterFirstBatch = requireConfirmationAfterFirstBatch;
        this.status = CampaignStatus.CREATED;
        this.currentBatchIndex = -1;
    }

    public String getCampaignId() { return campaignId; }
    public String getTargetFirmwareVersion() { return targetFirmwareVersion; }
    public List<List<String>> getBatches() { return batches; }
    public double getFailureThresholdPercent() { return failureThresholdPercent; }
    public boolean isControlled() { return controlled; }
    public boolean isRequireConfirmationAfterFirstBatch() { return requireConfirmationAfterFirstBatch; }
    public CampaignStatus getStatus() { return status; }
    public void setStatus(CampaignStatus status) { this.status = status; }
    public int getCurrentBatchIndex() { return currentBatchIndex; }
    public void setCurrentBatchIndex(int i) { this.currentBatchIndex = i; }
    public boolean isAutoPaused() { return autoPaused; }
    public void setAutoPaused(boolean autoPaused) { this.autoPaused = autoPaused; }
}
