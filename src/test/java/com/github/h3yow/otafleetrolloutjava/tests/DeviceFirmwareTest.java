package com.github.h3yow.otafleetrolloutjava.tests;

import com.github.h3yow.otafleetrolloutjava.dto.HeartbeatRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterFirmwareRequest;
import com.github.h3yow.otafleetrolloutjava.dto.VersionDistribution;
import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import com.github.h3yow.otafleetrolloutjava.model.Device;
import com.github.h3yow.otafleetrolloutjava.model.RejectReason;
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

class DeviceFirmwareTest {

    private static final Logger log = LoggerFactory.getLogger(DeviceFirmwareTest.class);

    private FleetStore store;
    private DeviceService deviceService;
    private FirmwareService firmwareService;

    @BeforeEach
    void setUp() {
        String path = "target/test-store-device-" + System.nanoTime() + ".json";
        new File(path).delete();
        store = new FleetStore();
        org.springframework.test.util.ReflectionTestUtils.setField(store, "storeFile", path);
        store.init();
        deviceService = new DeviceService(store);
        firmwareService = new FirmwareService(store);
    }

    private RegisterDeviceRequest deviceReq(String id, String platform, String version) {
        RegisterDeviceRequest r = new RegisterDeviceRequest();
        r.setDeviceId(id);
        r.setPlatform(platform);
        r.setCurrentVersion(version);
        return r;
    }

    @Test
    void register_missingFieldsRejectedWithDistinctReasons() {
        RegisterDeviceRequest noPlatform = deviceReq("d1", "  ", "1.0");
        RejectedException ex1 = assertThrows(RejectedException.class, () -> deviceService.register(noPlatform));
        log.info("[判定] 输入 platform='{}' -> reason={}", noPlatform.getPlatform(), ex1.getReason());
        assertEquals(RejectReason.MISSING_PLATFORM, ex1.getReason());

        RegisterDeviceRequest noVersion = deviceReq("d1", "linux-arm64", " ");
        RejectedException ex2 = assertThrows(RejectedException.class, () -> deviceService.register(noVersion));
        log.info("[判定] 输入 currentVersion='{}' -> reason={}", noVersion.getCurrentVersion(), ex2.getReason());
        assertEquals(RejectReason.MISSING_VERSION, ex2.getReason());
    }

    @Test
    void register_isIdempotentAndLkgIsInitialVersion() {
        deviceService.register(deviceReq("d1", "linux-arm64", "1.0.0"));
        Device again = deviceService.register(deviceReq("d1", "linux-arm64", "1.0.0"));
        log.info("[输入] 重复注册 d1; [判定] 返回同一设备, lkg={}", again.getLastKnownGoodVersion());
        assertEquals("1.0.0", again.getLastKnownGoodVersion());
        assertEquals(1, deviceService.all().size());
    }

    @Test
    void heartbeat_platformMismatchRejected() {
        deviceService.register(deviceReq("d1", "linux-arm64", "1.0.0"));
        HeartbeatRequest hb = new HeartbeatRequest();
        hb.setDeviceId("d1");
        hb.setPlatform("linux-x86_64");
        hb.setCurrentVersion("1.0.0");
        RejectedException ex = assertThrows(RejectedException.class, () -> deviceService.heartbeat(hb));
        log.info("[输入] heartbeat platform={} (注册平台=linux-arm64) -> reason={}", hb.getPlatform(), ex.getReason());
        assertEquals(RejectReason.PLATFORM_MISMATCH, ex.getReason());
    }

    @Test
    void firmware_registerAndQuery() {
        RegisterFirmwareRequest fw = new RegisterFirmwareRequest();
        fw.setFirmwareId("fw-2");
        fw.setPlatform("linux-arm64");
        fw.setVersion("2.0.0");
        fw.setMinBaseVersion("1.0.0");
        fw.setCompatiblePlatforms(List.of("linux-arm64"));
        firmwareService.register(fw);
        assertEquals("2.0.0", firmwareService.get("fw-2").getVersion());
        log.info("[输入] 注册固件 fw-2@2.0.0 minBase=1.0.0 -> 成功");

        VersionDistribution dist = deviceService.distribution(null);
        assertNotNull(dist);
    }
}
