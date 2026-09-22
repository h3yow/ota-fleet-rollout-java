package com.github.h3yow.otafleetrolloutjava.dto;

import java.util.List;

public class CreateCampaignRequest {
    private String campaignId;
    private String firmwareId;
    private Double failureRateThreshold;
    private List<List<String>> batches; // 每批设备 ID 列表

    public String getCampaignId() { return campaignId; }
    public void setCampaignId(String campaignId) { this.campaignId = campaignId; }
    public String getFirmwareId() { return firmwareId; }
    public void setFirmwareId(String firmwareId) { this.firmwareId = firmwareId; }
    public Double getFailureRateThreshold() { return failureRateThreshold; }
    public void setFailureRateThreshold(Double failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }
    public List<List<String>> getBatches() { return batches; }
    public void setBatches(List<List<String>> batches) { this.batches = batches; }
}
