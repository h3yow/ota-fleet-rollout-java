package com.github.h3yow.otafleetrolloutjava.tests;

import com.github.h3yow.otafleetrolloutjava.dto.CreateCampaignRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetrolloutjava.dto.ReportUpgradeRequest;
import com.github.h3yow.otafleetrolloutjava.exception.RejectReason;
import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import com.github.h3yow.otafleetrolloutjava.model.*;
import com.github.h3yow.otafleetrolloutjava.repository.CampaignRepository;
import com.github.h3yow.otafleetrolloutjava.repository.DeviceRepository;
import com.github.h3yow.otafleetrolloutjava.repository.FirmwareRepository;
import com.github.h3yow.otafleetrolloutjava.repository.UpgradeRecordRepository;
import com.github.h3yow.otafleetrolloutjava.service.CampaignService;
import com.github.h3yow.otafleetrolloutjava.service.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 受控发布严格验收、分批放行门禁与首批人工确认 */
class ControlledReleaseAndGateTest {

    private static final Logger log = LoggerFactory.getLogger(ControlledReleaseAndGateTest.class);

    private DeviceRepository deviceRepo;
    private CampaignRepository campaignRepo;
    private FirmwareRepository firmwareRepo;
    private UpgradeRecordRepository recordRepo;
    private DeviceService deviceService;
    private CampaignService campaignService;

    @BeforeEach
    void setUp() {
        deviceRepo = new DeviceRepository();
        campaignRepo = new CampaignRepository();
        firmwareRepo = new FirmwareRepository();
        recordRepo = new UpgradeRecordRepository();
        campaignService = new CampaignService(campaignRepo, deviceRepo, firmwareRepo, recordRepo);
        deviceService = new DeviceService(deviceRepo, campaignRepo, recordRepo, campaignService);
        firmwareRepo.save(new Firmware("2.0.0", "linux-arm64", "1.0.0", "sha256:aaa"));
        deviceService.register(new RegisterDeviceRequest("dev-1", "linux-arm64", "1.0.0"));
    }

    @Test
    void strictAcceptanceRejectsWrongReportedVersionAndAutoRollsBack() {
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-strict", "2.0.0", List.of(List.of("dev-1")), 100.0, true, false));
        campaignService.advance("camp-strict");

        log.info("[Test] input: controlled campaign, device reports success but version=1.0.0 != target 2.0.0");
        String result = deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-1", "camp-strict", 1L, true, "1.0.0", "linux-arm64"));

        Device device = deviceRepo.findById("dev-1").orElseThrow();
        UpgradeRecord record = recordRepo.find("camp-strict", "dev-1").orElseThrow();
        log.info("[Test] decision: result={} deviceVersion={} deviceStatus={} recordStatus={} failureReason={}",
                result, device.getCurrentVersion(), device.getStatus(), record.getStatus(),
                record.getFailureReason());

        // 明确失败结论，且已按回滚规则回到此前确认可用的 1.0.0
        assertEquals(UpgradeStatus.ROLLED_BACK.name(), result);
        assertEquals("1.0.0", device.getCurrentVersion(), "must not be left on an untrusted version");
        assertEquals("1.0.0", device.getLastConfirmedVersion());
        assertEquals(DeviceStatus.ROLLED_BACK, device.getStatus());
        assertNull(device.getActiveCampaignId());
        assertEquals(FailureReason.REPORTED_VERSION_MISMATCH, record.getFailureReason());
    }

    @Test
    void strictAcceptanceAcceptsExactTargetVersion() {
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-strict-ok", "2.0.0", List.of(List.of("dev-1")), 100.0, true, false));
        campaignService.advance("camp-strict-ok");

        log.info("[Test] input: controlled campaign, device reports success with exact target 2.0.0");
        String result = deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-1", "camp-strict-ok", 1L, true, "2.0.0", "linux-arm64"));
        log.info("[Test] decision: result={}", result);

        assertEquals(UpgradeStatus.SUCCEEDED.name(), result);
        assertEquals("2.0.0", deviceRepo.findById("dev-1").orElseThrow().getCurrentVersion());
    }

    @Test
    void nonControlledCampaignKeepsLegacyBehaviorForVersionMismatch() {
        // 非受控发布：即便上报版本与目标不同，成功仍按原行为放行
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-loose", "2.0.0", List.of(List.of("dev-1")), 100.0, false, false));
        campaignService.advance("camp-loose");

        log.info("[Test] input: non-controlled campaign, success report with version=1.5.0");
        String result = deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-1", "camp-loose", 1L, true, "1.5.0", "linux-arm64"));
        log.info("[Test] decision: result={} version={}", result,
                deviceRepo.findById("dev-1").orElseThrow().getCurrentVersion());

        assertEquals(UpgradeStatus.SUCCEEDED.name(), result);
        assertEquals("2.0.0", deviceRepo.findById("dev-1").orElseThrow().getCurrentVersion());
    }

    @Test
    void advanceRejectedWhenPreviousBatchNotSettled() {
        deviceService.register(new RegisterDeviceRequest("dev-2", "linux-arm64", "1.0.0"));
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-gate", "2.0.0",
                List.of(List.of("dev-1"), List.of("dev-2")), 100.0));
        campaignService.advance("camp-gate");
        // dev-1 尚未上报，批次0未结清

        log.info("[Test] input: advance to batch 1 while dev-1 still has no verdict");
        RejectedException ex = assertThrows(RejectedException.class,
                () -> campaignService.advance("camp-gate"));
        log.info("[Test] decision: reason={} message={}", ex.getReason(), ex.getMessage());
        assertEquals(RejectReason.PREVIOUS_BATCH_NOT_SETTLED, ex.getReason());

        // 结清批次0后可正常放行批次1
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-1", "camp-gate", 1L, true, "2.0.0", "linux-arm64"));
        Campaign after = campaignService.advance("camp-gate");
        assertEquals(1, after.getCurrentBatchIndex());
        assertEquals(DeviceStatus.UPGRADING,
                deviceRepo.findById("dev-2").orElseThrow().getStatus());
    }

    @Test
    void advanceBlockedAfterFailureStopConditionUntilResume() {
        deviceService.register(new RegisterDeviceRequest("dev-2", "linux-arm64", "1.0.0"));
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-stop", "2.0.0",
                List.of(List.of("dev-1", "dev-2")), 0.0, false, false));
        campaignService.advance("camp-stop");

        // 批次0包含两台，dev-1 先失败，失败率 100% 超阈值 0%，触发停下条件（设备仍未全部结清）
        log.info("[Test] input: dev-1 fails, threshold=0% -> stop condition triggered");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-1", "camp-stop", 1L, false, "1.0.0", "linux-arm64"));
        Campaign paused = campaignRepo.findById("camp-stop").orElseThrow();
        log.info("[Test] decision: status={} autoPaused={}", paused.getStatus(), paused.isAutoPaused());
        assertEquals(CampaignStatus.PAUSED, paused.getStatus());
        assertTrue(paused.isAutoPaused());

        RejectedException ex = assertThrows(RejectedException.class,
                () -> campaignService.advance("camp-stop"));
        log.info("[Test] advance rejected while stop condition active: reason={}", ex.getReason());
        assertEquals(RejectReason.STOP_CONDITION_TRIGGERED, ex.getReason());
    }
}
