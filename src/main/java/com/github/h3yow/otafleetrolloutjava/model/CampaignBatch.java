package com.github.h3yow.otafleetrolloutjava.model;

import java.util.ArrayList;
import java.util.List;

/** 灰度批次：一个活动包含多个有序批次，按顺序激活 */
public class CampaignBatch {
    private int index;
    private List<String> deviceIds = new ArrayList<>();
    private BatchStatus status = BatchStatus.PENDING;
    private long activatedAt;

    public CampaignBatch() {
    }

    public CampaignBatch(int index, List<String> deviceIds) {
        this.index = index;
        if (deviceIds != null) {
            this.deviceIds.addAll(deviceIds);
        }
    }

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }
    public List<String> getDeviceIds() { return deviceIds; }
    public void setDeviceIds(List<String> deviceIds) { this.deviceIds = deviceIds; }
    public BatchStatus getStatus() { return status; }
    public void setStatus(BatchStatus status) { this.status = status; }
    public long getActivatedAt() { return activatedAt; }
    public void setActivatedAt(long activatedAt) { this.activatedAt = activatedAt; }
}
