package com.github.h3yow.otafleetrolloutjava.dto;

import com.github.h3yow.otafleetrolloutjava.model.BatchStatus;

import java.util.List;

public class BatchView {
    private int index;
    private BatchStatus status;
    private List<String> deviceIds;

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }
    public BatchStatus getStatus() { return status; }
    public void setStatus(BatchStatus status) { this.status = status; }
    public List<String> getDeviceIds() { return deviceIds; }
    public void setDeviceIds(List<String> deviceIds) { this.deviceIds = deviceIds; }
}
