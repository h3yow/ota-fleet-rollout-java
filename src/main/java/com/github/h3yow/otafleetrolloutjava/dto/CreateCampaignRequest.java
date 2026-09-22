package com.github.h3yow.otafleetrolloutjava.dto;

import java.util.List;

public record CreateCampaignRequest(String campaignId, String targetFirmwareVersion,
                                    List<List<String>> batches, double failureThresholdPercent) {
}
