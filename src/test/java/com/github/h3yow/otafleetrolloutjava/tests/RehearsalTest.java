package com.github.h3yow.otafleetrolloutjava.tests;

import com.github.h3yow.otafleetrolloutjava.dto.CreateCampaignRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RehearsalBatchForecast;
import com.github.h3yow.otafleetrolloutjava.dto.RehearsalBlockedDevice;
import com.github.h3yow.otafleetrolloutjava.dto.RehearsalResult;
import com.github.h3yow.otafleetrolloutjava.exception.RejectReason;
import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import com.github.h3yow.otafleetrolloutjava.model.Firmware;
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
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 上线前彩排：无副作用验证与各类阻塞原因分辨 */
class RehearsalTest {

    private static final Logger log = LoggerFactory.getLogger(RehearsalTest.class);

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
    void healthyPlanIsExecutableAndLeavesNoSideEffects() {
        deviceService.register(new RegisterDeviceRequest("dev-1", "linux-arm64", "1.0.0"));
        deviceService.register(new RegisterDeviceRequest("dev-2", "linux-arm64", "1.2.0"));

        CreateCampaignRequest plan = new CreateCampaignRequest("camp-r", "2.0.0",
                List.of(List.of("dev-1"), List.of("dev-2")), 50.0, true, true);
        log.info("[Test] rehearsal input: target=2.0.0 batches=[[dev-1],[dev-2]] controlled+wait");
        RehearsalResult result = campaignService.rehearse(plan);

        log.info("[Test] rehearsal decision: executable={} forecasts={}",
                result.executable(),
                result.batches().stream().map(f -> "batch" + f.batchIndex()
                        + " covered=" + f.coveredCount()).toList());
        assertTrue(result.executable());
        assertEquals(2, result.batches().size());
        assertEquals(1, result.batches().get(0).coveredCount());
        assertEquals(1, result.batches().get(1).coveredCount());
        assertTrue(result.batches().get(0).blockedDevices().isEmpty());

        // 彩排不得留下任何活动、设备占用或升级记录
        assertTrue(campaignRepo.findAll().isEmpty(), "rehearsal must not persist a campaign");
        assertTrue(recordRepo.findByCampaign("camp-r").isEmpty(), "rehearsal must not create records");
        assertNull(deviceRepo.findById("dev-1").orElseThrow().getActiveCampaignId(),
                "rehearsal must not occupy devices");
    }

    @Test
    void blockedDevicesAreReportedWithDistinctReasonsPerBatch() {
        firmwareRepo.save(new Firmware("4.0.0", "linux-arm64", "2.0.0", "sha256:new"));
        deviceService.register(new RegisterDeviceRequest("dev-ok", "linux-arm64", "1.0.0"));
        // dev-low 版本 0.9.0，低于目标固件 minSourceVersion=1.0.0
        deviceService.register(new RegisterDeviceRequest("dev-low", "linux-arm64", "0.9.0"));

        // 另一进行中活动占用 dev-busy
        deviceService.register(new RegisterDeviceRequest("dev-busy", "linux-arm64", "1.0.0"));
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-other", "2.0.0", List.of(List.of("dev-busy")), 100.0));
        campaignService.advance("camp-other");

        CreateCampaignRequest plan = new CreateCampaignRequest("camp-plan", "2.0.0",
                List.of(
                        List.of("dev-ok", "ghost", "dev-low", "dev-busy", "dev-ok")),
                50.0);
        log.info("[Test] rehearsal input covers: ok / missing / low-version / busy / duplicate(ok again)");

        RehearsalResult result = campaignService.rehearse(plan);
        RehearsalBatchForecast batch = result.batches().get(0);
        Map<String, RehearsalBlockedDevice> byDevice = batch.blockedDevices().stream()
                .collect(Collectors.toMap(RehearsalBlockedDevice::deviceId, Function.identity(),
                        (a, b) -> a));

        log.info("[Test] decision: executable={} covered={}/{} blocked={}",
                result.executable(), batch.coveredCount(), batch.plannedCount(),
                batch.blockedDevices().stream().map(d -> d.deviceId() + "->" + d.reason()).toList());

        assertFalse(result.executable());
        assertEquals(5, batch.plannedCount());
        assertEquals(1, batch.coveredCount(), "only first occurrence of dev-ok is coverable");
        assertEquals(RejectReason.DEVICE_NOT_FOUND.name(), byDevice.get("ghost").reason());
        assertEquals(RejectReason.VERSION_CONSTRAINT_VIOLATED.name(), byDevice.get("dev-low").reason());
        assertEquals(RejectReason.DEVICE_BUSY_IN_OTHER_CAMPAIGN.name(), byDevice.get("dev-busy").reason());
        assertEquals(RejectReason.DUPLICATE_DEVICE_IN_PLAN.name(), byDevice.get("dev-ok").reason());

        // 彩排失败仍无副作用
        assertTrue(campaignRepo.findById("camp-plan").isEmpty());
        assertTrue(recordRepo.findByCampaign("camp-plan").isEmpty());
    }

    @Test
    void platformMismatchIsItsOwnBlockReason() {
        firmwareRepo.save(new Firmware("3.0.0", "windows-x64", "1.0.0", "sha256:win"));
        deviceService.register(new RegisterDeviceRequest("dev-arm", "linux-arm64", "1.0.0"));
        CreateCampaignRequest plan = new CreateCampaignRequest("camp-win", "3.0.0",
                List.of(List.of("dev-arm")), 50.0);

        RehearsalResult result = campaignService.rehearse(plan);
        assertFalse(result.executable());
        RehearsalBlockedDevice blocked = result.batches().get(0).blockedDevices().get(0);
        log.info("[Test] platform decision: device={} reason={} detail={}",
                blocked.deviceId(), blocked.reason(), blocked.detail());
        assertEquals("dev-arm", blocked.deviceId());
        assertEquals(RejectReason.INCOMPATIBLE_FIRMWARE.name(), blocked.reason());
    }

    @Test
    void rehearsalFailsFastWhenTargetFirmwareMissing() {
        deviceService.register(new RegisterDeviceRequest("dev-1", "linux-arm64", "1.0.0"));
        CreateCampaignRequest plan = new CreateCampaignRequest("camp-x", "9.9.9",
                List.of(List.of("dev-1")), 50.0);
        log.info("[Test] rehearsal with unknown target firmware 9.9.9 -> expect FIRMWARE_NOT_FOUND");
        RejectedException ex = assertThrows(RejectedException.class, () -> campaignService.rehearse(plan));
        log.info("[Test] decision: reason={}", ex.getReason());
        assertEquals(RejectReason.FIRMWARE_NOT_FOUND, ex.getReason());
        assertTrue(campaignRepo.findAll().isEmpty());
    }
}
