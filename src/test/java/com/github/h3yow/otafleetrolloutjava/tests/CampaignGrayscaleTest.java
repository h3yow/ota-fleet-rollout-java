package com.github.h3yow.otafleetrolloutjava.tests;

import com.github.h3yow.otafleetrolloutjava.dto.CreateCampaignRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterFirmwareRequest;
import com.github.h3yow.otafleetrolloutjava.dto.UpgradeReportRequest;
import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import com.github.h3yow.otafleetrolloutjava.model.BatchStatus;
import com.github.h3yow.otafleetrolloutjava.model.CampaignStatus;
import com.github.h3yow.otafleetrolloutjava.model.ReportOutcome;
import com.github.h3yow.otafleetrolloutjava.model.RejectReason;
import com.github.h3yow.otafleetrolloutjava.service.CampaignService;
import com.github.h3yow.otafleetrolloutjava.service.DeviceService;
import com.github.h3yow.otafleetrolloutjava.service.FirmwareService;
import com.github.h3yow.otafleetrolloutjava.store.FleetStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CampaignGrayscaleTest {

    private static final Logger log = LoggerFactory.getLogger(CampaignGrayscaleTest.class);

    private FleetStore store;
    private DeviceService deviceService;
    private FirmwareService firmwareService;
    private CampaignService campaignService;

    @BeforeEach
    void setUp() {
        String path = "target/test-store-gray-" + System.nanoTime() + ".json";
        new File(path).delete();
        store = new FleetStore();
        org.springframework.test.util.ReflectionTestUtils.setField(store, "storeFile", path);
        store.init();
        deviceService = new DeviceService(store);
        firmwareService = new FirmwareService(store);
        campaignService = new CampaignService(store);
    }

    private void device(String id, String platform, String version) {
        RegisterDeviceRequest d = new RegisterDeviceRequest();
        d.setDeviceId(id);
        d.setPlatform(platform);
        d.setCurrentVersion(version);
        deviceService.register(d);
    }

    private void firmware(String id, String platform, String version, String minBase) {
        RegisterFirmwareRequest fw = new RegisterFirmwareRequest();
        fw.setFirmwareId(id);
        fw.setPlatform(platform);
        fw.setVersion(version);
        fw.setMinBaseVersion(minBase);
        firmwareService.register(fw);
    }

    private UpgradeReportRequest success(String campaignId, String deviceId, long seq) {
        UpgradeReportRequest r = new UpgradeReportRequest();
        r.setCampaignId(campaignId);
        r.setDeviceId(deviceId);
        r.setSeq(seq);
        r.setOutcome(ReportOutcome.SUCCESS);
        r.setReportedVersion("2.0.0");
        r.setPlatform("linux-arm64");
        return r;
    }

    private UpgradeReportRequest failure(String campaignId, String deviceId, long seq, String platform) {
        UpgradeReportRequest r = new UpgradeReportRequest();
        r.setCampaignId(campaignId);
        r.setDeviceId(deviceId);
        r.setSeq(seq);
        r.setOutcome(ReportOutcome.FAILURE);
        r.setReportedVersion("2.0.0");
        r.setPlatform(platform);
        return r;
    }

    @Test
    void batchesAdvanceInOrderAndBlockWhenUnsettled() {
        device("d1", "linux-arm64", "1.0.0");
        device("d2", "linux-arm64", "1.0.0");
        firmware("fw2", "linux-arm64", "2.0.0", "1.0.0");

        CreateCampaignRequest req = new CreateCampaignRequest();
        req.setCampaignId("c1");
        req.setFirmwareId("fw2");
        req.setFailureRateThreshold(0.9);
        req.setBatches(List.of(List.of("d1"), List.of("d2")));
        campaignService.create(req);

        // 未激活时批次 1 设备无任务，不能直接跳到第二批发
        campaignService.activateNextBatch("c1");
        assertEquals(CampaignStatus.RUNNING, campaignService.get("c1").getStatus());
        assertEquals(BatchStatus.ACTIVE, campaignService.get("c1").getBatches().get(0).getStatus());
        assertEquals(BatchStatus.PENDING, campaignService.get("c1").getBatches().get(1).getStatus());

        RejectedException ex = assertThrows(RejectedException.class, () -> campaignService.activateNextBatch("c1"));
        log.info("[输入] 第一批未全部终态即推进 -> reason={}", ex.getReason());
        assertEquals(RejectReason.CAMPAIGN_NOT_RUNNABLE, ex.getReason());

        campaignService.report(success("c1", "d1", 1));
        assertEquals(BatchStatus.COMPLETED, campaignService.get("c1").getBatches().get(0).getStatus());
        campaignService.activateNextBatch("c1");
        assertEquals(1, campaignService.get("c1").getActiveBatchIndex());
        assertEquals(BatchStatus.ACTIVE, campaignService.get("c1").getBatches().get(1).getStatus());
        log.info("[判定] 第二批次已激活, d2 任务状态={}", campaignService.taskView("c1", "d2").getStatus());

        campaignService.report(success("c1", "d2", 1));
        // 最后一批全部终态，活动自动完成；之后再推进拒绝
        assertEquals(CampaignStatus.COMPLETED, campaignService.get("c1").getStatus());
        RejectedException done = assertThrows(RejectedException.class,
                () -> campaignService.activateNextBatch("c1"));
        log.info("[判定] 全部批次终态后再推进 -> reason={}, campaign=COMPLETED", done.getReason());
        assertEquals(RejectReason.NO_MORE_BATCHES, done.getReason());
    }

    @Test
    void failureRateAboveThresholdAutoPausesAndBlocksNextBatch() {
        for (int i = 1; i <= 4; i++) {
            device("d" + i, "linux-arm64", "1.0.0");
        }
        firmware("fw2", "linux-arm64", "2.0.0", "1.0.0");

        CreateCampaignRequest req = new CreateCampaignRequest();
        req.setCampaignId("c1");
        req.setFirmwareId("fw2");
        req.setFailureRateThreshold(0.5);
        req.setBatches(List.of(List.of("d1", "d2"), List.of("d3", "d4")));
        campaignService.create(req);
        campaignService.activateNextBatch("c1");

        campaignService.report(success("c1", "d1", 1));
        var fr = campaignService.report(failure("c1", "d2", 1, "linux-arm64"));
        log.info("[输入] 第一批 1成功1失败, failureRate={} threshold=0.5 -> autoPaused={}",
                campaignService.get("c1").getStats().getFailureRate(), fr.isCampaignAutoPaused());
        assertTrue(fr.isCampaignAutoPaused());
        assertEquals(CampaignStatus.PAUSED, campaignService.get("c1").getStatus());
        assertEquals("AUTO_FAILURE_RATE", campaignService.get("c1").getPauseReason());

        // 自动暂停状态下继续下发必须被拒绝
        RejectedException ex = assertThrows(RejectedException.class, () -> campaignService.activateNextBatch("c1"));
        log.info("[输入] 自动暂停后尝试推进 -> reason={}", ex.getReason());
        assertEquals(RejectReason.CAMPAIGN_NOT_RUNNABLE, ex.getReason());

        // 第二批次设备仍为 ASSIGNED，未被下发
        assertEquals(com.github.h3yow.otafleetrolloutjava.model.TaskStatus.ASSIGNED,
                campaignService.taskView("c1", "d3").getStatus());

        // resume 后可继续
        campaignService.resume("c1");
        campaignService.activateNextBatch("c1");
        assertEquals(1, campaignService.get("c1").getActiveBatchIndex());
        log.info("[判定] resume 后第二批次成功激活");
    }

    @Test
    void deviceCannotJoinTwoActiveCampaigns() {
        device("d1", "linux-arm64", "1.0.0");
        firmware("fw2", "linux-arm64", "2.0.0", "1.0.0");
        firmware("fw2b", "linux-arm64", "2.0.1", "1.0.0");

        CreateCampaignRequest c1 = new CreateCampaignRequest();
        c1.setCampaignId("c1");
        c1.setFirmwareId("fw2");
        c1.setFailureRateThreshold(0.9);
        c1.setBatches(List.of(List.of("d1")));
        campaignService.create(c1);

        CreateCampaignRequest c2 = new CreateCampaignRequest();
        c2.setCampaignId("c2");
        c2.setFirmwareId("fw2b");
        c2.setFailureRateThreshold(0.9);
        c2.setBatches(List.of(List.of("d1")));
        RejectedException ex = assertThrows(RejectedException.class, () -> campaignService.create(c2));
        log.info("[输入] 设备 d1 在 c1 进行中又加入 c2 -> reason={}", ex.getReason());
        assertEquals(RejectReason.DEVICE_IN_ANOTHER_CAMPAIGN, ex.getReason());

        // c1 完成后同一设备可参与新活动
        campaignService.activateNextBatch("c1");
        campaignService.report(success("c1", "d1", 1));
        assertEquals(CampaignStatus.COMPLETED, campaignService.get("c1").getStatus());
        campaignService.create(c2);
        log.info("[判定] c1 完成后 d1 可加入 c2");
    }

    @Test
    void compatibilityConstraintsBlockDispatchAtCreation() {
        device("old", "linux-arm64", "0.9.0");
        device("x86", "linux-x86_64", "1.0.0");
        firmware("fw2", "linux-arm64", "2.0.0", "1.0.0");

        CreateCampaignRequest tooOld = new CreateCampaignRequest();
        tooOld.setCampaignId("c1");
        tooOld.setFirmwareId("fw2");
        tooOld.setFailureRateThreshold(0.9);
        tooOld.setBatches(List.of(List.of("old")));
        RejectedException e1 = assertThrows(RejectedException.class, () -> campaignService.create(tooOld));
        log.info("[输入] 设备 0.9.0 < minBase 1.0.0 -> reason={}", e1.getReason());
        assertEquals(RejectReason.FIRMWARE_BASE_VERSION_TOO_OLD, e1.getReason());

        CreateCampaignRequest wrongPlatform = new CreateCampaignRequest();
        wrongPlatform.setCampaignId("c2");
        wrongPlatform.setFirmwareId("fw2");
        wrongPlatform.setFailureRateThreshold(0.9);
        wrongPlatform.setBatches(List.of(List.of("x86")));
        RejectedException e2 = assertThrows(RejectedException.class, () -> campaignService.create(wrongPlatform));
        log.info("[输入] x86 设备下发 arm64 固件 -> reason={}", e2.getReason());
        assertEquals(RejectReason.FIRMWARE_PLATFORM_UNSUPPORTED, e2.getReason());
    }

    @Test
    void pauseAndTerminateTransitionsAreGuarded() {
        device("d1", "linux-arm64", "1.0.0");
        firmware("fw2", "linux-arm64", "2.0.0", "1.0.0");
        CreateCampaignRequest req = new CreateCampaignRequest();
        req.setCampaignId("c1");
        req.setFirmwareId("fw2");
        req.setFailureRateThreshold(0.9);
        req.setBatches(List.of(List.of("d1")));
        campaignService.create(req);

        campaignService.pause("c1");
        assertEquals(CampaignStatus.PAUSED, campaignService.get("c1").getStatus());
        RejectedException doublePause = assertThrows(RejectedException.class, () -> campaignService.pause("c1"));
        log.info("[输入] 重复暂停 -> reason={}", doublePause.getReason());
        assertEquals(RejectReason.CAMPAIGN_ALREADY_PAUSED, doublePause.getReason());

        campaignService.terminate("c1");
        assertEquals(CampaignStatus.TERMINATED, campaignService.get("c1").getStatus());
        RejectedException advance = assertThrows(RejectedException.class, () -> campaignService.activateNextBatch("c1"));
        log.info("[输入] 终止后推进 -> reason={}", advance.getReason());
        assertEquals(RejectReason.CAMPAIGN_TERMINATED, advance.getReason());
    }
}
