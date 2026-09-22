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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 失败回滚、版本兼容约束与机群版本分布 */
class RollbackAndCompatibilityTest {

    private static final Logger log = LoggerFactory.getLogger(RollbackAndCompatibilityTest.class);

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
    }

    @Test
    void failedDeviceRollsBackToLastConfirmedVersion() {
        deviceService.register(new RegisterDeviceRequest("dev-1", "linux-arm64", "1.5.0"));
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-1", "2.0.0", List.of(List.of("dev-1")), 100.0));
        campaignService.advance("camp-1");

        log.info("[Test] input: dev-1 reports failure at seq=1, lastConfirmed=1.5.0");
        deviceService.reportUpgradeResult(
                new ReportUpgradeRequest("dev-1", "camp-1", 1L, false, "1.5.0", "linux-arm64"));
        assertEquals(DeviceStatus.FAILED, deviceRepo.findById("dev-1").orElseThrow().getStatus());

        campaignService.rollbackDevice("camp-1", "dev-1");
        Device device = deviceRepo.findById("dev-1").orElseThrow();
        log.info("[Test] decision: rolled back currentVersion={} status={}",
                device.getCurrentVersion(), device.getStatus());
        assertEquals("1.5.0", device.getCurrentVersion());
        assertEquals(DeviceStatus.ROLLED_BACK, device.getStatus());
        assertEquals(UpgradeStatus.ROLLED_BACK,
                recordRepo.find("camp-1", "dev-1").orElseThrow().getStatus());
        assertNull(device.getActiveCampaignId());

        // 回滚后机群版本分布可准确查询
        Map<String, Long> dist = deviceService.fleetVersionDistribution();
        log.info("[Test] fleet version distribution after rollback: {}", dist);
        assertEquals(Map.of("1.5.0", 1L), dist);
    }

    @Test
    void rollbackOfNonFailedRecordIsRejected() {
        deviceService.register(new RegisterDeviceRequest("dev-1", "linux-arm64", "1.5.0"));
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-1", "2.0.0", List.of(List.of("dev-1")), 100.0));
        campaignService.advance("camp-1");
        deviceService.reportUpgradeResult(
                new ReportUpgradeRequest("dev-1", "camp-1", 1L, true, "2.0.0", "linux-arm64"));

        log.info("[Test] input: rollback a SUCCEEDED record -> expect ILLEGAL_DEVICE_STATE");
        RejectedException ex = assertThrows(RejectedException.class,
                () -> campaignService.rollbackDevice("camp-1", "dev-1"));
        log.info("[Test] decision: reason={} message={}", ex.getReason(), ex.getMessage());
        assertEquals(RejectReason.ILLEGAL_DEVICE_STATE, ex.getReason());
    }

    @Test
    void incompatiblePlatformFirmwareIsNotDispatched() {
        firmwareRepo.save(new Firmware("3.0.0", "windows-x64", "1.0.0", "sha256:bbb"));
        deviceService.register(new RegisterDeviceRequest("dev-1", "linux-arm64", "1.5.0"));

        log.info("[Test] input: campaign targets windows-x64 firmware for linux-arm64 device");
        RejectedException ex = assertThrows(RejectedException.class,
                () -> campaignService.createCampaign(new CreateCampaignRequest(
                        "camp-bad", "3.0.0", List.of(List.of("dev-1")), 100.0)));
        log.info("[Test] decision: reason={} message={}", ex.getReason(), ex.getMessage());
        assertEquals(RejectReason.INCOMPATIBLE_FIRMWARE, ex.getReason());
    }

    @Test
    void versionConstraintViolationIsNotDispatched() {
        firmwareRepo.save(new Firmware("4.0.0", "linux-arm64", "2.0.0", "sha256:ccc"));
        deviceService.register(new RegisterDeviceRequest("dev-1", "linux-arm64", "1.5.0"));

        log.info("[Test] input: device at 1.5.0, firmware requires minSourceVersion=2.0.0");
        RejectedException ex = assertThrows(RejectedException.class,
                () -> campaignService.createCampaign(new CreateCampaignRequest(
                        "camp-low", "4.0.0", List.of(List.of("dev-1")), 100.0)));
        log.info("[Test] decision: reason={} message={}", ex.getReason(), ex.getMessage());
        assertEquals(RejectReason.VERSION_CONSTRAINT_VIOLATED, ex.getReason());
    }

    @Test
    void deviceCannotJoinTwoInFlightCampaigns() {
        deviceService.register(new RegisterDeviceRequest("dev-1", "linux-arm64", "1.5.0"));
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-a", "2.0.0", List.of(List.of("dev-1")), 100.0));
        campaignService.advance("camp-a");

        log.info("[Test] input: second campaign targets dev-1 while camp-a in flight");
        RejectedException ex = assertThrows(RejectedException.class,
                () -> campaignService.createCampaign(new CreateCampaignRequest(
                        "camp-b", "2.0.0", List.of(List.of("dev-1")), 100.0)));
        log.info("[Test] decision: reason={} message={}", ex.getReason(), ex.getMessage());
        assertEquals(RejectReason.DEVICE_BUSY_IN_OTHER_CAMPAIGN, ex.getReason());
    }

    @Test
    void fleetVersionDistributionReflectsMixedOutcomes() {
        deviceService.register(new RegisterDeviceRequest("dev-ok", "linux-arm64", "1.0.0"));
        deviceService.register(new RegisterDeviceRequest("dev-bad", "linux-arm64", "1.0.0"));
        deviceService.register(new RegisterDeviceRequest("dev-idle", "linux-arm64", "1.2.0"));
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-m", "2.0.0", List.of(List.of("dev-ok", "dev-bad")), 100.0));
        campaignService.advance("camp-m");

        deviceService.reportUpgradeResult(
                new ReportUpgradeRequest("dev-ok", "camp-m", 1L, true, "2.0.0", "linux-arm64"));
        deviceService.reportUpgradeResult(
                new ReportUpgradeRequest("dev-bad", "camp-m", 1L, false, "1.0.0", "linux-arm64"));
        campaignService.rollbackDevice("camp-m", "dev-bad");

        Map<String, Long> dist = deviceService.fleetVersionDistribution();
        log.info("[Test] mixed outcomes distribution: {}", dist);
        assertEquals(Map.of("1.0.0", 1L, "1.2.0", 1L, "2.0.0", 1L), dist);
    }
}
