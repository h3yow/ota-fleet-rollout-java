package com.github.h3yow.otafleetrolloutjava.tests;

import com.github.h3yow.otafleetrolloutjava.dto.CreateCampaignRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetrolloutjava.dto.ReportUpgradeRequest;
import com.github.h3yow.otafleetrolloutjava.exception.RejectReason;
import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import com.github.h3yow.otafleetrolloutjava.model.Device;
import com.github.h3yow.otafleetrolloutjava.model.Firmware;
import com.github.h3yow.otafleetrolloutjava.model.UpgradeStatus;
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

/** 重复、乱序、迟到上报与缺失/不兼容信息的拒绝 */
class DeviceReportTest {

    private static final Logger log = LoggerFactory.getLogger(DeviceReportTest.class);

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
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-1", "2.0.0", List.of(List.of("dev-1")), 50.0));
        campaignService.advance("camp-1");
    }

    @Test
    void duplicateReportIsRejectedAndNotDoubleCounted() {
        ReportUpgradeRequest report = new ReportUpgradeRequest(
                "dev-1", "camp-1", 1L, true, "2.0.0", "linux-arm64");
        log.info("[Test] input: first report seq=1 success=true");
        String first = deviceService.reportUpgradeResult(report);
        assertEquals(UpgradeStatus.SUCCEEDED.name(), first);
        assertEquals("2.0.0", deviceRepo.findById("dev-1").orElseThrow().getCurrentVersion());

        log.info("[Test] input: duplicate report seq=1 (same sequence) -> expect STALE_REPORT");
        RejectedException dup = assertThrows(RejectedException.class,
                () -> deviceService.reportUpgradeResult(report));
        log.info("[Test] decision: reason={} message={}", dup.getReason(), dup.getMessage());
        assertEquals(RejectReason.STALE_REPORT, dup.getReason());

        // 升级次数不重复计数：记录仍为唯一一条且状态未变
        assertEquals(1, recordRepo.findByCampaign("camp-1").size());
        assertEquals(UpgradeStatus.SUCCEEDED,
                recordRepo.find("camp-1", "dev-1").orElseThrow().getStatus());
    }

    @Test
    void outOfOrderAndLateReportsDoNotRegressState() {
        log.info("[Test] input: seq=5 success=true applied first (higher sequence wins)");
        deviceService.reportUpgradeResult(
                new ReportUpgradeRequest("dev-1", "camp-1", 5L, true, "2.0.0", "linux-arm64"));
        Device device = deviceRepo.findById("dev-1").orElseThrow();
        assertEquals("2.0.0", device.getCurrentVersion());

        log.info("[Test] input: late seq=3 success=false -> expect STALE_REPORT, no state regression");
        RejectedException late = assertThrows(RejectedException.class,
                () -> deviceService.reportUpgradeResult(
                        new ReportUpgradeRequest("dev-1", "camp-1", 3L, false, "1.0.0", "linux-arm64")));
        log.info("[Test] decision: reason={} message={}", late.getReason(), late.getMessage());
        assertEquals(RejectReason.STALE_REPORT, late.getReason());
        assertEquals("2.0.0", device.getCurrentVersion());
        assertEquals(UpgradeStatus.SUCCEEDED,
                recordRepo.find("camp-1", "dev-1").orElseThrow().getStatus());
    }

    @Test
    void missingVersionOrPlatformIsRejected() {
        log.info("[Test] input: report with null reportedVersion");
        RejectedException ex = assertThrows(RejectedException.class,
                () -> deviceService.reportUpgradeResult(
                        new ReportUpgradeRequest("dev-1", "camp-1", 1L, true, null, "linux-arm64")));
        log.info("[Test] decision: reason={}", ex.getReason());
        assertEquals(RejectReason.MISSING_VERSION_OR_PLATFORM, ex.getReason());

        log.info("[Test] input: report with blank platform");
        ex = assertThrows(RejectedException.class,
                () -> deviceService.reportUpgradeResult(
                        new ReportUpgradeRequest("dev-1", "camp-1", 1L, true, "2.0.0", " ")));
        assertEquals(RejectReason.MISSING_VERSION_OR_PLATFORM, ex.getReason());
    }

    @Test
    void platformMismatchIsRejectedWithDistinctReason() {
        log.info("[Test] input: report platform=windows-x64 but registered=linux-arm64");
        RejectedException ex = assertThrows(RejectedException.class,
                () -> deviceService.reportUpgradeResult(
                        new ReportUpgradeRequest("dev-1", "camp-1", 1L, true, "2.0.0", "windows-x64")));
        log.info("[Test] decision: reason={} message={}", ex.getReason(), ex.getMessage());
        assertEquals(RejectReason.PLATFORM_MISMATCH, ex.getReason());
    }

    @Test
    void reportForUnknownDeviceOrCampaignIsRejected() {
        RejectedException ex = assertThrows(RejectedException.class,
                () -> deviceService.reportUpgradeResult(
                        new ReportUpgradeRequest("ghost", "camp-1", 1L, true, "2.0.0", "linux-arm64")));
        assertEquals(RejectReason.DEVICE_NOT_FOUND, ex.getReason());

        ex = assertThrows(RejectedException.class,
                () -> deviceService.reportUpgradeResult(
                        new ReportUpgradeRequest("dev-1", "camp-x", 1L, true, "2.0.0", "linux-arm64")));
        assertEquals(RejectReason.CAMPAIGN_NOT_FOUND, ex.getReason());
    }
}
