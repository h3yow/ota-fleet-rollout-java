package com.github.h3yow.otafleetrolloutjava.dto;

import java.util.List;

public record CreateCampaignRequest(String campaignId, String targetFirmwareVersion,
                                    List<List<String>> batches, double failureThresholdPercent,
                                    boolean controlled,
                                    boolean requireConfirmationAfterFirstBatch) {

    /** 非受控发布、无首批确认点的兼容构造器（既有调用与旧 JSON 均走这里） */
    public CreateCampaignRequest(String campaignId, String targetFirmwareVersion,
                                 List<List<String>> batches, double failureThresholdPercent) {
        this(campaignId, targetFirmwareVersion, batches, failureThresholdPercent, false, false);
    }
}
