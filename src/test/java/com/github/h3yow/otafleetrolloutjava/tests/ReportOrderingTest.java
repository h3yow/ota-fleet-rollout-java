package com.github.h3yow.otafleetrolloutjava.tests;

import com.github.h3yow.otafleetrolloutjava.dto.CreateCampaignRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterFirmwareRequest;
import com.github.h3yow.otafleetrolloutjava.dto.ReportResult;
import com.github.h3yow.otafleetrolloutjava.dto.UpgradeReportRequest;
import com.github.h3yow.otafleetrolloutjava.dto.VersionDistribution;
import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import com.github.h3yow.otafleetrolloutjava.model.ReportOutcome;
import com.github.h3yow.otafleetrolloutjava.model.RejectReason;
import com.github.h3yow.otafleetrolloutjava.model.TaskStatus;
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

/** 覆盖：重复/乱序/迟到上报不重复计数、状态不倒退；版本/平台缺失或不兼容可区分拒绝；失败回滚。 */
class ReportOrderingTest {

    private static final Logger log = LoggerFactory.getLogger(ReportOrderingTest.class);

    private FleetStore store;
    private DeviceService deviceService;
    private FirmwareService firmwareService;
    private CampaignService campaignService;

    @BeforeEach
    void setUp() {
        String path = "target/test-store-report-" + System.nanoTime() + ".json";
        new File(path).delete();
        store = new FleetStore();
        org.springframework.test.util.ReflectionTestUtils.setField(store, "storeFile", path);
        store.init();
        deviceService = new DeviceService(store);
        firmwareService = new FirmwareService(store);
        campaignService = new CampaignService(store);
    }

    private void seedDevice(String id, String platform, String version) {
        RegisterDeviceRequest d = new RegisterDeviceRequest();
        d.setDeviceId(id);
        d.setPlatform(platform);
        d.setCurrentVersion(version);
        deviceService.register(d);
    }

    private void seedFirmware(String fwId, String platform, String version, String minBase) {
        RegisterFirmwareRequest fw = new RegisterFirmwareRequest();
        fw.setFirmwareId(fwId);
        fw.setPlatform(platform);
        fw.setVersion(version);
        fw.setMinBaseVersion(minBase);
        firmwareService.register(fw);
    }

    private void seedCampaign(String campaignId, String fwId, double threshold, String... deviceIds) {
        CreateCampaignRequest req = new CreateCampaignRequest();
        req.setCampaignId(campaignId);
        req.setFirmwareId(fwId);
        req.setFailureRateThreshold(threshold);
        req.setBatches(List.of(java.util.Arrays.asList(deviceIds)));
        campaignService.create(req);
        campaignService.activateNextBatch(campaignId);
    }

    private UpgradeReportRequest report(String campaignId, String deviceId, long seq,
                                        ReportOutcome outcome, String version, String platform) {
        UpgradeReportRequest r = new UpgradeReportRequest();
        r.setCampaignId(campaignId);
        r.setDeviceId(deviceId);
        r.setSeq(seq);
        r.setOutcome(outcome);
        r.setReportedVersion(version);
        r.setPlatform(platform);
        return r;
    }

    @Test
    void duplicateAndStaleReports_doNotRecountOrRegress() {
        seedDevice("d1", "linux-arm64", "1.0.0");
        seedFirmware("fw2", "linux-arm64", "2.0.0", "1.0.0");
        seedCampaign("c1", "fw2", 0.9, "d1");

        ReportResult ok = campaignService.report(report("c1", "d1", 10, ReportOutcome.SUCCESS, "2.0.0", "linux-arm64"));
        log.info("[输入] d1 seq=10 SUCCESS 2.0.0 -> accepted={}, status={}, attempts={}",
                ok.isAccepted(), ok.getTaskStatus(), ok.getAttempts());
        assertTrue(ok.isAccepted());
        assertEquals(1, ok.getAttempts());
        assertEquals(TaskStatus.SUCCESS, ok.getTaskStatus());

        // 重复上报（同 seq）
        ReportResult dup = campaignService.report(report("c1", "d1", 10, ReportOutcome.SUCCESS, "2.0.0", "linux-arm64"));
        log.info("[输入] d1 seq=10 重复上报 -> accepted={}, reason={}, attempts={}",
                dup.isAccepted(), dup.getRejectReason(), dup.getAttempts());
        assertFalse(dup.isAccepted());
        assertEquals(RejectReason.DUPLICATE_REPORT.name(), dup.getRejectReason());

        // 迟到/乱序上报（更小 seq），且声称失败 —— 不得把 SUCCESS 拉回失败
        ReportResult stale = campaignService.report(report("c1", "d1", 5, ReportOutcome.FAILURE, "1.0.0", "linux-arm64"));
        log.info("[输入] d1 seq=5 迟到 FAILURE -> accepted={}, reason={}, status 保持={}",
                stale.isAccepted(), stale.getRejectReason(), stale.getTaskStatus());
        assertFalse(stale.isAccepted());
        assertEquals(RejectReason.STALE_REPORT.name(), stale.getRejectReason());
        assertEquals(TaskStatus.SUCCESS, stale.getTaskStatus());

        // 更大 seq 但任务已终态：仍按重复拒绝，不重复计数
        ReportResult late2 = campaignService.report(report("c1", "d1", 99, ReportOutcome.FAILURE, "1.0.0", "linux-arm64"));
        log.info("[输入] d1 seq=99 终态后迟到 -> accepted={}, reason={}", late2.isAccepted(), late2.getRejectReason());
        assertFalse(late2.isAccepted());
        assertEquals(RejectReason.DUPLICATE_REPORT.name(), late2.getRejectReason());

        assertEquals(1, campaignService.taskView("c1", "d1").getAttempts());
        assertEquals("2.0.0", deviceService.get("d1").getCurrentVersion());
    }

    @Test
    void missingOrIncompatibleFields_distinctRejectReasons() {
        seedDevice("d1", "linux-arm64", "1.0.0");
        seedFirmware("fw2", "linux-arm64", "2.0.0", "1.0.0");
        seedCampaign("c1", "fw2", 0.9, "d1");

        UpgradeReportRequest noVersion = report("c1", "d1", 1, ReportOutcome.SUCCESS, " ", "linux-arm64");
        RejectedException e1 = assertThrows(RejectedException.class, () -> campaignService.report(noVersion));
        log.info("[输入] reportedVersion 缺失 -> reason={}", e1.getReason());
        assertEquals(RejectReason.MISSING_VERSION, e1.getReason());

        UpgradeReportRequest noPlatform = report("c1", "d1", 1, ReportOutcome.SUCCESS, "2.0.0", "");
        RejectedException e2 = assertThrows(RejectedException.class, () -> campaignService.report(noPlatform));
        log.info("[输入] platform 缺失 -> reason={}", e2.getReason());
        assertEquals(RejectReason.MISSING_PLATFORM, e2.getReason());

        // 使用独立设备/活动验证平台不匹配
        seedDevice("d2", "linux-arm64", "1.0.0");
        seedCampaign("c2", "fw2", 0.9, "d2");
        UpgradeReportRequest wrongPlatform = report("c2", "d2", 1, ReportOutcome.SUCCESS, "2.0.0", "linux-x86_64");
        RejectedException e3 = assertThrows(RejectedException.class, () -> campaignService.report(wrongPlatform));
        log.info("[输入] platform=linux-x86_64 与注册不符 -> reason={}", e3.getReason());
        assertEquals(RejectReason.PLATFORM_MISMATCH, e3.getReason());

        // SUCCESS 但上报版本与目标不一致：作为校验不通过 -> 回滚
        seedDevice("d3", "linux-arm64", "1.0.0");
        seedCampaign("c3", "fw2", 0.9, "d3");
        ReportResult mismatch = campaignService.report(
                report("c3", "d3", 1, ReportOutcome.SUCCESS, "9.9.9", "linux-arm64"));
        log.info("[输入] SUCCESS 但 reportedVersion=9.9.9 target=2.0.0 -> accepted={}, status={}, rollbackTo={}",
                mismatch.isAccepted(), mismatch.getTaskStatus(), mismatch.getRollbackToVersion());
        assertTrue(mismatch.isAccepted());
        assertEquals(TaskStatus.ROLLED_BACK, mismatch.getTaskStatus());
        assertEquals("1.0.0", mismatch.getRollbackToVersion());
        assertEquals("1.0.0", deviceService.get("d3").getCurrentVersion());
    }

    @Test
    void failure_rollsBackToLastKnownGood() {
        seedDevice("d1", "linux-arm64", "1.0.0");
        seedFirmware("fw2", "linux-arm64", "2.0.0", "1.0.0");
        seedCampaign("c1", "fw2", 0.9, "d1");

        ReportResult failure = campaignService.report(
                report("c1", "d1", 7, ReportOutcome.FAILURE, "2.0.0-rc", "linux-arm64"));
        log.info("[输入] d1 seq=7 FAILURE -> status={}, rollbackTo={}",
                failure.getTaskStatus(), failure.getRollbackToVersion());
        assertEquals(TaskStatus.ROLLED_BACK, failure.getTaskStatus());
        assertEquals("1.0.0", deviceService.get("d1").getCurrentVersion());
        assertEquals("1.0.0", deviceService.get("d1").getLastKnownGoodVersion());

        VersionDistribution dist = deviceService.distribution("linux-arm64");
        log.info("[判定] 回滚后机群版本分布: total={}, byVersion={}", dist.getTotalDevices(), dist.getByVersion());
        assertEquals(1, dist.getTotalDevices());
        assertEquals(1, dist.getByVersion().get("1.0.0"));
        assertNull(dist.getByVersion().get("2.0.0-rc"));
    }

    @Test
    void reportWithoutDispatchedTask_rejected() {
        seedDevice("d1", "linux-arm64", "1.0.0");
        seedFirmware("fw2", "linux-arm64", "2.0.0", "1.0.0");
        CreateCampaignRequest req = new CreateCampaignRequest();
        req.setCampaignId("c1");
        req.setFirmwareId("fw2");
        req.setFailureRateThreshold(0.9);
        req.setBatches(List.of(List.of("d1")));
        campaignService.create(req); // 未激活批次，任务仍是 ASSIGNED

        RejectedException ex = assertThrows(RejectedException.class,
                () -> campaignService.report(report("c1", "d1", 1, ReportOutcome.SUCCESS, "2.0.0", "linux-arm64")));
        log.info("[输入] 批次未激活即上报 -> reason={}", ex.getReason());
        assertEquals(RejectReason.NO_ACTIVE_TASK, ex.getReason());
    }
}
