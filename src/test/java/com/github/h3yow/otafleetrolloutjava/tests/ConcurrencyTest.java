package com.github.h3yow.otafleetrolloutjava.tests;

import com.github.h3yow.otafleetrolloutjava.dto.CreateCampaignRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterFirmwareRequest;
import com.github.h3yow.otafleetrolloutjava.dto.ReportResult;
import com.github.h3yow.otafleetrolloutjava.dto.UpgradeReportRequest;
import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import com.github.h3yow.otafleetrolloutjava.model.CampaignStatus;
import com.github.h3yow.otafleetrolloutjava.model.ReportOutcome;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 并发上报（含重复）与并发推进：计数与状态必须一致。 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    private FleetStore store;
    private DeviceService deviceService;
    private FirmwareService firmwareService;
    private CampaignService campaignService;

    @BeforeEach
    void setUp() {
        String path = "target/test-store-conc-" + System.nanoTime() + ".json";
        new File(path).delete();
        store = new FleetStore();
        org.springframework.test.util.ReflectionTestUtils.setField(store, "storeFile", path);
        store.init();
        deviceService = new DeviceService(store);
        firmwareService = new FirmwareService(store);
        campaignService = new CampaignService(store);
    }

    private void seed(int n) {
        for (int i = 1; i <= n; i++) {
            RegisterDeviceRequest d = new RegisterDeviceRequest();
            d.setDeviceId("d" + i);
            d.setPlatform("linux-arm64");
            d.setCurrentVersion("1.0.0");
            deviceService.register(d);
        }
        RegisterFirmwareRequest fw = new RegisterFirmwareRequest();
        fw.setFirmwareId("fw2");
        fw.setPlatform("linux-arm64");
        fw.setVersion("2.0.0");
        fw.setMinBaseVersion("1.0.0");
        firmwareService.register(fw);
    }

    private CreateCampaignRequest campaign(String id, int batchSize, double threshold) {
        List<String> all = new java.util.ArrayList<>();
        for (int i = 1; i <= batchSize; i++) {
            all.add("d" + i);
        }
        CreateCampaignRequest req = new CreateCampaignRequest();
        req.setCampaignId(id);
        req.setFirmwareId("fw2");
        req.setFailureRateThreshold(threshold);
        req.setBatches(List.of(all));
        return req;
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

    @Test
    void concurrentDuplicateReports_countedOnce() throws Exception {
        int devices = 20;
        seed(devices);
        campaignService.create(campaign("c1", devices, 0.99));
        campaignService.activateNextBatch("c1");

        int threadsPerDevice = 5;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(devices * threadsPerDevice);
        AtomicInteger accepted = new AtomicInteger();
        ConcurrentLinkedQueue<String> reasons = new ConcurrentLinkedQueue<>();

        for (int i = 1; i <= devices; i++) {
            String deviceId = "d" + i;
            for (int t = 0; t < threadsPerDevice; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        ReportResult rr = campaignService.report(success("c1", deviceId, 1L));
                        if (rr.isAccepted()) {
                            accepted.incrementAndGet();
                        } else {
                            reasons.add(rr.getRejectReason());
                        }
                    } catch (RejectedException e) {
                        reasons.add(e.getReason().name());
                    } catch (Exception e) {
                        log.error("unexpected", e);
                    } finally {
                        done.countDown();
                    }
                });
            }
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "all report threads should finish");
        pool.shutdown();

        log.info("[输入] {} 台设备 x {} 个并发重复上报 -> accepted={}, rejected={}, reasons={}",
                devices, threadsPerDevice, accepted.get(), reasons.size(),
                reasons.stream().distinct().toList());
        assertEquals(devices, accepted.get(), "每台设备只应有一次上报被接受");
        assertTrue(reasons.stream().allMatch("DUPLICATE_REPORT"::equals),
                "其余全部是重复拒绝，实际: " + reasons.stream().distinct().toList());

        int totalAttempts = 0;
        for (int i = 1; i <= devices; i++) {
            assertEquals(TaskStatus.SUCCESS, campaignService.taskView("c1", "d" + i).getStatus());
            assertEquals(1, campaignService.taskView("c1", "d" + i).getAttempts());
            totalAttempts += campaignService.taskView("c1", "d" + i).getAttempts();
        }
        assertEquals(devices, totalAttempts, "attempts 总数必须等于设备数，不重复计数");
        assertEquals(CampaignStatus.COMPLETED, campaignService.get("c1").getStatus());
        assertEquals(devices, campaignService.get("c1").getStats().getSuccess());
        assertEquals(0, campaignService.get("c1").getStats().getRolledBack());
        log.info("[判定] stats={}", campaignService.get("c1").getStats());
    }

    @Test
    void concurrentAdvance_onlyOneBatchActivatesAndSettlesConsistently() throws Exception {
        int batchSize = 10;
        seed(batchSize);
        campaignService.create(campaign("c1", batchSize, 0.99));

        int advancers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(advancers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(advancers);
        AtomicInteger okAdvance = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < advancers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    campaignService.activateNextBatch("c1");
                    okAdvance.incrementAndGet();
                } catch (RejectedException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[输入] {} 个并发 advance(初始状态) -> 成功={}, 拒绝={}", advancers, okAdvance.get(), rejected.get());
        assertEquals(1, okAdvance.get(), "只有一个线程能激活第一批");
        assertEquals(advancers - 1, rejected.get());
        assertEquals(0, campaignService.get("c1").getActiveBatchIndex());
        assertEquals(batchSize, campaignService.get("c1").getStats().getDispatched());

        // 并发补齐上报后活动完成，计数守恒：success + dispatched(应为0) = total
        ExecutorService reporters = Executors.newFixedThreadPool(10);
        CountDownLatch rdone = new CountDownLatch(batchSize);
        for (int i = 1; i <= batchSize; i++) {
            String id = "d" + i;
            reporters.submit(() -> {
                try {
                    campaignService.report(success("c1", id, 1L));
                } catch (RejectedException e) {
                    log.debug("reporter rejected for {}: {}", id, e.getReason());
                } finally {
                    rdone.countDown();
                }
            });
        }
        assertTrue(rdone.await(30, TimeUnit.SECONDS));
        reporters.shutdown();

        var stats = campaignService.get("c1").getStats();
        log.info("[判定] 并发上报后 stats: total={}, success={}, dispatched={}, rolledBack={}, failed={}, attempts={}",
                stats.getTotal(), stats.getSuccess(), stats.getDispatched(),
                stats.getRolledBack(), stats.getFailed(), stats.getAttempts());
        assertEquals(batchSize, stats.getTotal());
        assertEquals(batchSize, stats.getSuccess());
        assertEquals(0, stats.getDispatched());
        assertEquals(batchSize, stats.getAttempts());
        assertEquals(CampaignStatus.COMPLETED, campaignService.get("c1").getStatus());
    }
}
