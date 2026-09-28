package com.github.h3yow.otafleetrolloutjava.tests;

import com.github.h3yow.otafleetrolloutjava.dto.CreateCampaignRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetrolloutjava.dto.ReportUpgradeRequest;
import com.github.h3yow.otafleetrolloutjava.exception.RejectReason;
import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import com.github.h3yow.otafleetrolloutjava.model.Campaign;
import com.github.h3yow.otafleetrolloutjava.model.CampaignStatus;
import com.github.h3yow.otafleetrolloutjava.model.DeviceStatus;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 首批结清后等待人工确认，以及重复/并发确认的幂等性 */
class FirstBatchConfirmationTest {

    private static final Logger log = LoggerFactory.getLogger(FirstBatchConfirmationTest.class);

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
        deviceService.register(new RegisterDeviceRequest("dev-2", "linux-arm64", "1.0.0"));
    }

    private void createWaitingCampaign(String id) {
        campaignService.createCampaign(new CreateCampaignRequest(
                id, "2.0.0", List.of(List.of("dev-1"), List.of("dev-2")),
                100.0, false, true));
        campaignService.advance(id);
    }

    @Test
    void waitsAtGateAfterFirstBatchSettledAndConfirmsBeforeSecondDispatch() {
        createWaitingCampaign("camp-w");

        log.info("[Test] input: dev-1 reports success; campaign should stop at AWAITING_CONFIRMATION");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-1", "camp-w", 1L, true, "2.0.0", "linux-arm64"));

        Campaign waiting = campaignRepo.findById("camp-w").orElseThrow();
        log.info("[Test] decision: status={} firstBatchConfirmed={}",
                waiting.getStatus(), waiting.isFirstBatchConfirmed());
        assertEquals(CampaignStatus.AWAITING_CONFIRMATION, waiting.getStatus());
        // 确认前批次1设备未被占用、未下发
        assertNull(deviceRepo.findById("dev-2").orElseThrow().getActiveCampaignId());
        assertEquals(DeviceStatus.IDLE, deviceRepo.findById("dev-2").orElseThrow().getStatus());

        log.info("[Test] input: advance while awaiting confirmation -> expect rejection");
        RejectedException ex = assertThrows(RejectedException.class,
                () -> campaignService.advance("camp-w"));
        log.info("[Test] decision: reason={}", ex.getReason());
        assertEquals(RejectReason.AWAITING_FIRST_BATCH_CONFIRMATION, ex.getReason());

        log.info("[Test] input: manual confirm, then advance dispatches batch 1 exactly once");
        Campaign confirmed = campaignService.confirmFirstBatch("camp-w");
        assertEquals(CampaignStatus.RUNNING, confirmed.getStatus());
        assertTrue(confirmed.isFirstBatchConfirmed());
        // 确认本身不下发
        assertNull(deviceRepo.findById("dev-2").orElseThrow().getActiveCampaignId());

        Campaign advanced = campaignService.advance("camp-w");
        assertEquals(1, advanced.getCurrentBatchIndex());
        assertEquals("camp-w", deviceRepo.findById("dev-2").orElseThrow().getActiveCampaignId());
    }

    @Test
    void duplicateConfirmationIsRejected() {
        createWaitingCampaign("camp-d");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-1", "camp-d", 1L, true, "2.0.0", "linux-arm64"));
        campaignService.confirmFirstBatch("camp-d");

        log.info("[Test] input: second confirm after first confirmed -> expect CONFIRMATION_NOT_REQUIRED");
        RejectedException ex = assertThrows(RejectedException.class,
                () -> campaignService.confirmFirstBatch("camp-d"));
        log.info("[Test] decision: reason={}", ex.getReason());
        assertEquals(RejectReason.CONFIRMATION_NOT_REQUIRED, ex.getReason());
    }

    @Test
    void confirmOnCampaignWithoutGateIsRejected() {
        // waitAfterFirstBatch=false 的普通活动，首批结清后直接可推进，不需要确认
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-nogate", "2.0.0", List.of(List.of("dev-1"), List.of("dev-2")), 100.0));
        campaignService.advance("camp-nogate");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-1", "camp-nogate", 1L, true, "2.0.0", "linux-arm64"));

        log.info("[Test] input: confirm a campaign not configured with the gate");
        RejectedException ex = assertThrows(RejectedException.class,
                () -> campaignService.confirmFirstBatch("camp-nogate"));
        log.info("[Test] decision: reason={}", ex.getReason());
        assertEquals(RejectReason.CONFIRMATION_NOT_REQUIRED, ex.getReason());
        // 普通活动不受影响，直接放行
        Campaign advanced = campaignService.advance("camp-nogate");
        assertEquals(1, advanced.getCurrentBatchIndex());
    }

    @Test
    void waitingGateManualPauseAndStopConditionAreDistinguishable() {
        // 场景1：等待确认
        createWaitingCampaign("camp-wait");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-1", "camp-wait", 1L, true, "2.0.0", "linux-arm64"));
        Campaign waiting = campaignRepo.findById("camp-wait").orElseThrow();
        log.info("[Test] state1 (first batch settled, waitAfterFirstBatch=true): status={} autoPaused={}",
                waiting.getStatus(), waiting.isAutoPaused());
        assertEquals(CampaignStatus.AWAITING_CONFIRMATION, waiting.getStatus());
        assertFalse(waiting.isAutoPaused());

        // 场景2：人工暂停（首批下发后、结清前手动 pause）
        createWaitingCampaign("camp-manual");
        campaignService.pause("camp-manual");
        Campaign manual = campaignRepo.findById("camp-manual").orElseThrow();
        log.info("[Test] state2 (manual pause): status={} autoPaused={}",
                manual.getStatus(), manual.isAutoPaused());
        assertEquals(CampaignStatus.PAUSED, manual.getStatus());
        assertFalse(manual.isAutoPaused());

        // 场景3：失败率触发停下条件（阈值 0%，一台失败即触发）
        deviceService.register(new RegisterDeviceRequest("dev-3", "linux-arm64", "1.0.0"));
        deviceService.register(new RegisterDeviceRequest("dev-4", "linux-arm64", "1.0.0"));
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-stop", "2.0.0", List.of(List.of("dev-3", "dev-4")),
                0.0, false, false));
        campaignService.advance("camp-stop");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-3", "camp-stop", 1L, false, "1.0.0", "linux-arm64"));
        Campaign stopped = campaignRepo.findById("camp-stop").orElseThrow();
        log.info("[Test] state3 (failure threshold breached): status={} autoPaused={}",
                stopped.getStatus(), stopped.isAutoPaused());
        assertEquals(CampaignStatus.PAUSED, stopped.getStatus());
        assertTrue(stopped.isAutoPaused());

        // 人工暂停 resume 后回到 RUNNING 且不清除门禁语义；自动暂停 resume 清除停下标记
        campaignService.resume("camp-manual");
        assertEquals(CampaignStatus.RUNNING,
                campaignRepo.findById("camp-manual").orElseThrow().getStatus());
    }

    @Test
    void concurrentConfirmAndAdvanceDispatchSecondBatchExactlyOnce() throws Exception {
        createWaitingCampaign("camp-cc");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-1", "camp-cc", 1L, true, "2.0.0", "linux-arm64"));
        assertEquals(CampaignStatus.AWAITING_CONFIRMATION,
                campaignRepo.findById("camp-cc").orElseThrow().getStatus());

        int advanceThreads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(advanceThreads + 1);
        CountDownLatch advanceStart = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger advanced = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        // 一批推进线程先在门口等待
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < advanceThreads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    advanceStart.await();
                    campaignService.advance("camp-cc");
                    advanced.incrementAndGet();
                } catch (RejectedException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // 确认与推进竞争：确认完成后放开所有推进线程
        Future<?> confirmFuture = pool.submit(() -> {
            campaignService.confirmFirstBatch("camp-cc");
            confirmed.incrementAndGet();
            advanceStart.countDown();
        });
        confirmFuture.get(10, TimeUnit.SECONDS);
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 确认后重复确认也必须被拒绝
        RejectedException dup = assertThrows(RejectedException.class,
                () -> campaignService.confirmFirstBatch("camp-cc"));
        log.info("[Test] duplicate confirm after gate cleared rejected: reason={}", dup.getReason());
        assertEquals(RejectReason.CONFIRMATION_NOT_REQUIRED, dup.getReason());

        log.info("[Test] concurrent confirm/advance: confirmed={} advanced={} rejected={}",
                confirmed.get(), advanced.get(), rejected.get());
        // 判定依据：confirm 恰好成功一次；批次1恰好被一个线程下发
        assertEquals(1, confirmed.get());
        assertEquals(1, advanced.get());
        assertEquals(advanceThreads - 1, rejected.get());
        assertTrue(recordRepo.find("camp-cc", "dev-2").isPresent());
        Campaign campaign = campaignRepo.findById("camp-cc").orElseThrow();
        assertEquals(1, campaign.getCurrentBatchIndex());
    }
}
