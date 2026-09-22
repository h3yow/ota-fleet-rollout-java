package com.github.h3yow.otafleetrolloutjava.tests;

import com.github.h3yow.otafleetrolloutjava.dto.CreateCampaignRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterFirmwareRequest;
import com.github.h3yow.otafleetrolloutjava.model.CampaignStatus;
import com.github.h3yow.otafleetrolloutjava.model.TaskStatus;
import com.github.h3yow.otafleetrolloutjava.service.CampaignService;
import com.github.h3yow.otafleetrolloutjava.service.DeviceService;
import com.github.h3yow.otafleetrolloutjava.service.FirmwareService;
import com.github.h3yow.otafleetrolloutjava.service.VersionSupport;
import com.github.h3yow.otafleetrolloutjava.store.FleetStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceVersionTest {

    private static final Logger log = LoggerFactory.getLogger(PersistenceVersionTest.class);

    @Test
    void snapshotReloadRestoresState() {
        String path = "target/test-store-persist-" + System.nanoTime() + ".json";
        new File(path).delete();

        FleetStore s1 = new FleetStore();
        ReflectionTestUtils.setField(s1, "storeFile", path);
        s1.init();
        DeviceService ds1 = new DeviceService(s1);
        FirmwareService fs1 = new FirmwareService(s1);
        CampaignService cs1 = new CampaignService(s1);

        RegisterDeviceRequest d = new RegisterDeviceRequest();
        d.setDeviceId("d1");
        d.setPlatform("linux-arm64");
        d.setCurrentVersion("1.0.0");
        ds1.register(d);
        RegisterFirmwareRequest fw = new RegisterFirmwareRequest();
        fw.setFirmwareId("fw2");
        fw.setPlatform("linux-arm64");
        fw.setVersion("2.0.0");
        fw.setMinBaseVersion("1.0.0");
        fs1.register(fw);
        CreateCampaignRequest c = new CreateCampaignRequest();
        c.setCampaignId("c1");
        c.setFirmwareId("fw2");
        c.setFailureRateThreshold(0.5);
        c.setBatches(List.of(List.of("d1")));
        cs1.create(c);
        cs1.activateNextBatch("c1");
        assertTrue(new File(path).length() > 0, "快照文件应已落盘");
        log.info("[输入] 完成注册/创建/激活并落盘: {}", path);

        // 用新的 store 实例模拟进程重启
        FleetStore s2 = new FleetStore();
        ReflectionTestUtils.setField(s2, "storeFile", path);
        s2.init();
        DeviceService ds2 = new DeviceService(s2);
        CampaignService cs2 = new CampaignService(s2);

        assertEquals("1.0.0", ds2.get("d1").getLastKnownGoodVersion());
        assertEquals(CampaignStatus.RUNNING, cs2.get("c1").getStatus());
        assertEquals(TaskStatus.DISPATCHED, cs2.taskView("c1", "d1").getStatus());
        log.info("[判定] 重启恢复: device.lkg={}, campaign.status={}, task.status={}",
                ds2.get("d1").getLastKnownGoodVersion(), cs2.get("c1").getStatus(),
                cs2.taskView("c1", "d1").getStatus());
        new File(path).delete();
    }

    @Test
    void versionComparison() {
        assertTrue(VersionSupport.compare("1.10.0", "1.9.0") > 0);
        assertTrue(VersionSupport.compare("1.2", "1.2.0") == 0);
        assertTrue(VersionSupport.compare("0.9", "1.0") < 0);
        assertTrue(VersionSupport.gte("1.0.0", "1.0.0"));
        assertFalse(VersionSupport.gte("0.9.9", "1.0.0"));
        log.info("[判定] 版本比较 1.10.0>1.9.0, 1.2==1.2.0, 0.9<1.0 全部符合预期");
    }
}
