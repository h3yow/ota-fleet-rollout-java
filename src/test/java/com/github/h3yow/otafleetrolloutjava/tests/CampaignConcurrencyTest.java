package com.github.h3yow.otafleetrolloutjava.tests;

import com.github.h3yow.otafleetrolloutjava.dto.CreateCampaignRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetrolloutjava.dto.ReportUpgradeRequest;
import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import com.github.h3yow.otafleetrolloutjava.model.Campaign;
import com.github.h3yow.otafleetrolloutjava.model.CampaignStatus;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 并发推进、并发上报与失败率自动暂停 */
class CampaignConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(CampaignConcurrencyTest.class);

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
    void concurrentAdvanceOnlyOneThreadAdvancesEachBatch() throws Exception {
        for (int i = 0; i < 6; i++) {
            deviceService.register(new RegisterDeviceRequest("dev-" + i, "linux-arm64", "1.0.0"));
        }
        campaignService.createCampaign(new CreateCampaignRequest("camp-c", "2.0.0",
                List.of(List.of("dev-0", "dev-1"), List.of("dev-2", "dev-3"), List.of("dev-4", "dev-5")), 100.0));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger advanced = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    campaignService.advance("camp-c");
                    advanced.incrementAndGet();
                } catch (RejectedException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        Campaign campaign = campaignRepo.findById("camp-c").orElseThrow();
        log.info("[Test] concurrent advance: threads={} advanced={} rejected={} finalBatchIndex={}",
                threads, advanced.get(), rejected.get(), campaign.getCurrentBatchIndex());
        // 判定依据：每次只有一个线程能推进一个批次，推进次数 == 当前批次序号+1，且不超过批次数
        assertEquals(campaign.getCurrentBatchIndex() + 1, advanced.get());
        assertTrue(campaign.getCurrentBatchIndex() < 3);
        assertEquals(threads - advanced.get(), rejected.get());
        // 已下发批次的每台设备恰好一条升级记录
        long dispatchedDevices = recordRepo.findByCampaign("camp-c").stream()
                .map(r -> r.getDeviceId()).distinct().count();
        assertEquals(recordRepo.findByCampaign("camp-c").size(), dispatchedDevices);
    }

    @Test
    void concurrentDuplicateReportsCountedOnce() throws Exception {
        deviceService.register(new RegisterDeviceRequest("dev-x", "linux-arm64", "1.0.0"));
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-r", "2.0.0", List.of(List.of("dev-x")), 100.0));
        campaignService.advance("camp-r");

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger stale = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    deviceService.reportUpgradeResult(
                            new ReportUpgradeRequest("dev-x", "camp-r", 1L, true, "2.0.0", "linux-arm64"));
                    applied.incrementAndGet();
                } catch (RejectedException e) {
                    stale.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        log.info("[Test] concurrent duplicate reports: threads={} applied={} stale={}",
                threads, applied.get(), stale.get());
        // 判定依据：同一序号并发上报，恰好一次生效
        assertEquals(1, applied.get());
        assertEquals(threads - 1, stale.get());
        assertEquals(UpgradeStatus.SUCCEEDED,
                recordRepo.find("camp-r", "dev-x").orElseThrow().getStatus());
        assertEquals(CampaignStatus.COMPLETED,
                campaignRepo.findById("camp-r").orElseThrow().getStatus());
    }

    @Test
    void failureRateAboveThresholdAutoPausesCampaign() {
        for (int i = 0; i < 4; i++) {
            deviceService.register(new RegisterDeviceRequest("dev-" + i, "linux-arm64", "1.0.0"));
        }
        campaignService.createCampaign(new CreateCampaignRequest("camp-f", "2.0.0",
                List.of(List.of("dev-0", "dev-1", "dev-2", "dev-3"), List.of()), 50.0));
        campaignService.advance("camp-f");

        log.info("[Test] input: 3 of 4 devices report failure, threshold=50%");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest("dev-0", "camp-f", 1L, false, "1.0.0", "linux-arm64"));
        deviceService.reportUpgradeResult(new ReportUpgradeRequest("dev-1", "camp-f", 1L, false, "1.0.0", "linux-arm64"));
        deviceService.reportUpgradeResult(new ReportUpgradeRequest("dev-2", "camp-f", 1L, false, "1.0.0", "linux-arm64"));

        Campaign campaign = campaignRepo.findById("camp-f").orElseThrow();
        log.info("[Test] decision: status={} autoPaused={} (failure rate 3/3=100% > 50%)",
                campaign.getStatus(), campaign.isAutoPaused());
        assertEquals(CampaignStatus.PAUSED, campaign.getStatus());
        assertTrue(campaign.isAutoPaused());

        // 自动暂停后不得继续下发下一批
        RejectedException ex = assertThrows(RejectedException.class,
                () -> campaignService.advance("camp-f"));
        log.info("[Test] advance while auto-paused rejected: reason={}", ex.getReason());
    }
}
