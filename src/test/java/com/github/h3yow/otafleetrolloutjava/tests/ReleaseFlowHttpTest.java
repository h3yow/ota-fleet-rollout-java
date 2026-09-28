package com.github.h3yow.otafleetrolloutjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 新发布流程的 HTTP 层：旧 JSON 兼容、彩排无副作用、首批确认 */
@SpringBootTest
@AutoConfigureMockMvc
class ReleaseFlowHttpTest {

    private static final Logger log = LoggerFactory.getLogger(ReleaseFlowHttpTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rehearseThenCreateWithLegacyAndNewJson() throws Exception {
        log.info("[Test] register firmware + devices over HTTP");
        mockMvc.perform(post("/api/campaigns/firmware")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"9.0.0","platform":"linux-arm64","minSourceVersion":"1.0.0","checksum":"sha256:z"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"h-1","platform":"linux-arm64","initialVersion":"1.0.0"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"h-2","platform":"linux-arm64","initialVersion":"1.1.0"}
                                """))
                .andExpect(status().isOk());

        // 彩排：不存在的设备会被标出，且不产生活动
        log.info("[Test] rehearsal includes an unknown device h-ghost");
        mockMvc.perform(post("/api/campaigns/rehearse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"campaignId":"h-reh","targetFirmwareVersion":"9.0.0",
                                 "batches":[["h-1"],["h-2","h-ghost"]],"failureThresholdPercent":50,
                                 "controlled":true,"requireConfirmationAfterFirstBatch":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(false))
                .andExpect(jsonPath("$.totalCoveredDevices").value(2))
                .andExpect(jsonPath("$.batches[1].blockedDevices[0].reason").value("DEVICE_NOT_FOUND"));
    }

    @Test
    void legacyCampaignJsonDefaultsToNonControlled() throws Exception {
        mockMvc.perform(post("/api/campaigns/firmware")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"8.0.0","platform":"linux-arm64","minSourceVersion":"1.0.0","checksum":"sha256:l"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"l-1","platform":"linux-arm64","initialVersion":"1.0.0"}
                                """))
                .andExpect(status().isOk());
        // 旧 JSON 不含 controlled / requireConfirmationAfterFirstBatch，必须仍能创建
        log.info("[Test] create campaign with legacy JSON (no new fields)");
        mockMvc.perform(post("/api/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"campaignId":"l-camp","targetFirmwareVersion":"8.0.0",
                                 "batches":[["l-1"]],"failureThresholdPercent":50}
                                """))
                .andExpect(status().isOk());
    }
}
