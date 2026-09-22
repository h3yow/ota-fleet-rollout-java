package com.github.h3yow.otafleetrolloutjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.otafleetrolloutjava.dto.CreateCampaignRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterFirmwareRequest;
import com.github.h3yow.otafleetrolloutjava.dto.UpgradeReportRequest;
import com.github.h3yow.otafleetrolloutjava.model.ReportOutcome;
import com.github.h3yow.otafleetrolloutjava.service.CampaignService;
import com.github.h3yow.otafleetrolloutjava.service.DeviceService;
import com.github.h3yow.otafleetrolloutjava.service.FirmwareService;
import com.github.h3yow.otafleetrolloutjava.store.FleetStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.File;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.github.h3yow.otafleetrolloutjava.web.GlobalExceptionHandler;
import com.github.h3yow.otafleetrolloutjava.web.OtaController;

class WebApiTest {

    private static final Logger log = LoggerFactory.getLogger(WebApiTest.class);
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        String path = "target/test-store-web-" + System.nanoTime() + ".json";
        new File(path).delete();
        FleetStore store = new FleetStore();
        ReflectionTestUtils.setField(store, "storeFile", path);
        store.init();
        DeviceService deviceService = new DeviceService(store);
        FirmwareService firmwareService = new FirmwareService(store);
        CampaignService campaignService = new CampaignService(store);
        mvc = MockMvcBuilders.standaloneSetup(new OtaController(deviceService, firmwareService, campaignService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private RegisterDeviceRequest device(String id, String platform, String version) {
        RegisterDeviceRequest d = new RegisterDeviceRequest();
        d.setDeviceId(id);
        d.setPlatform(platform);
        d.setCurrentVersion(version);
        return d;
    }

    @Test
    void rejectedRequestsCarryDistinctReasonCode() throws Exception {
        // 注册缺平台 -> 400 + MISSING_PLATFORM
        String missingPlatform = json.writeValueAsString(device("d1", " ", "1.0"));
        mvc.perform(post("/api/ota/devices").contentType(MediaType.APPLICATION_JSON).content(missingPlatform))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value("MISSING_PLATFORM"));
        log.info("[判定] HTTP 注册缺平台 -> code=MISSING_PLATFORM");

        mvc.perform(post("/api/ota/devices").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(device("d1", "linux-arm64", "1.0.0"))))
                .andExpect(status().isOk());

        // 不存在设备 -> 400 + DEVICE_NOT_FOUND
        mvc.perform(get("/api/ota/devices/nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
        log.info("[判定] HTTP 查询不存在设备 -> code=DEVICE_NOT_FOUND");
    }

    @Test
    void fullHappyPathOverHttp() throws Exception {
        mvc.perform(post("/api/ota/devices").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(device("d1", "linux-arm64", "1.0.0"))))
                .andExpect(status().isOk());

        RegisterFirmwareRequest fw = new RegisterFirmwareRequest();
        fw.setFirmwareId("fw2");
        fw.setPlatform("linux-arm64");
        fw.setVersion("2.0.0");
        fw.setMinBaseVersion("1.0.0");
        mvc.perform(post("/api/ota/firmwares").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fw)))
                .andExpect(status().isOk());

        CreateCampaignRequest c = new CreateCampaignRequest();
        c.setCampaignId("c1");
        c.setFirmwareId("fw2");
        c.setFailureRateThreshold(0.5);
        c.setBatches(List.of(List.of("d1")));
        mvc.perform(post("/api/ota/campaigns").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(c)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        mvc.perform(post("/api/ota/campaigns/c1/advance")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        UpgradeReportRequest report = new UpgradeReportRequest();
        report.setCampaignId("c1");
        report.setDeviceId("d1");
        report.setSeq(1L);
        report.setOutcome(ReportOutcome.SUCCESS);
        report.setReportedVersion("2.0.0");
        report.setPlatform("linux-arm64");
        String body = mvc.perform(post("/api/ota/reports").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(report)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.taskStatus").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        log.info("[判定] HTTP 成功上报返回: attempts={}, status={}",
                node.path("data").path("attempts").asInt(), node.path("data").path("taskStatus").asText());

        mvc.perform(get("/api/ota/devices/versions?platform=linux-arm64"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byVersion['2.0.0']").value(1));
        log.info("[判定] HTTP 版本分布 byVersion.2.0.0=1");
    }
}
