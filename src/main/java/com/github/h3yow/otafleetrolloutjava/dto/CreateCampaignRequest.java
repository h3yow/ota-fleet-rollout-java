package com.github.h3yow.otafleetrolloutjava.dto;

import java.util.List;

/**
 * 创建分批灰度活动。
 *
 * @param controlled           是否受控发布（严格验收）：设备必须实际装到目标固件版本才算成功
 * @param waitAfterFirstBatch  是否在首批结清后停在“等待确认”状态，人工确认后才下发后续批次
 */
public record CreateCampaignRequest(String campaignId, String targetFirmwareVersion,
                                    List<List<String>> batches, double failureThresholdPercent,
                                    boolean controlled, boolean waitAfterFirstBatch) {

    /** 兼容旧调用：非受控发布、首批后不等待确认 */
    public CreateCampaignRequest(String campaignId, String targetFirmwareVersion,
                                 List<List<String>> batches, double failureThresholdPercent) {
        this(campaignId, targetFirmwareVersion, batches, failureThresholdPercent, false, false);
    }
}
