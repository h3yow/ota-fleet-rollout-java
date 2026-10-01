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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 首批人工确认在暂停/恢复后必须保持有效：
 * 已给出的确认不能被暂停或恢复抹掉，恢复后无需再次确认即可继续推进；
 * 重复确认、确认/暂停/恢复/推进交叉时，同一批设备只能下发一次。
 */
class FirstBatchConfirmationPersistenceTest {

    private static final Logger log = LoggerFactory.getLogger(FirstBatchConfirmationPersistenceTest.class);

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

    /** 首批结清 -> 确认 -> 手动暂停 -> 恢复：确认保持有效，无需再次确认即可推进第二批 */
    @Test
    void confirmationSurvivesPauseAndResumeAndAdvanceProceeds() {
        for (int i = 0; i < 4; i++) {
            deviceService.register(new RegisterDeviceRequest("dev-" + i, "linux-arm64", "1.0.0"));
        }
        campaignService.createCampaign(new CreateCampaignRequest("camp-keep", "2.0.0",
                List.of(List.of("dev-0", "dev-1"), List.of("dev-2", "dev-3")),
                100.0, false, true));
        campaignService.advance("camp-keep");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-0", "camp-keep", 1L, true, "2.0.0", "linux-arm64"));
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-1", "camp-keep", 1L, true, "2.0.0", "linux-arm64"));
        assertEquals(CampaignStatus.AWAITING_CONFIRMATION,
                campaignRepo.findById("camp-keep").orElseThrow().getStatus());

        campaignService.confirmFirstBatch("camp-keep");
        Campaign confirmed = campaignRepo.findById("camp-keep").orElseThrow();
        assertTrue(confirmed.isFirstBatchConfirmed());

        // 人工暂停
        campaignService.pause("camp-keep");
        Campaign paused = campaignRepo.findById("camp-keep").orElseThrow();
        assertEquals(CampaignStatus.PAUSED, paused.getStatus());
        assertFalse(paused.isAutoPaused(), "manual pause must keep autoPaused=false");
        assertTrue(paused.isFirstBatchConfirmed(), "pause must not erase the manual confirmation");

        // 暂停期间推进被拦截，且提示是手动暂停，而不是等待确认
        RejectedException blocked = assertThrows(RejectedException.class,
                () -> campaignService.advance("camp-keep"));
        assertEquals(RejectReason.ILLEGAL_CAMPAIGN_STATE, blocked.getReason());
        assertTrue(blocked.getMessage().contains("manually paused"),
                "stop reason must remain distinguishable: " + blocked.getMessage());

        // 恢复：必须回到 RUNNING 而不是 AWAITING_CONFIRMATION，且无需再次确认
        Campaign resumed = campaignService.resume("camp-keep");
        log.info("[Test] after resume status={} confirmed={}", resumed.getStatus(), resumed.isFirstBatchConfirmed());
        assertEquals(CampaignStatus.RUNNING, resumed.getStatus());
        assertTrue(resumed.isFirstBatchConfirmed());

        // 恢复后直接推进第二批成功，不被要求再次确认
        Campaign advanced = campaignService.advance("camp-keep");
        assertEquals(1, advanced.getCurrentBatchIndex());
        assertEquals(DeviceStatus.UPGRADING, deviceRepo.findById("dev-2").orElseThrow().getStatus());
        assertEquals(DeviceStatus.UPGRADING, deviceRepo.findById("dev-3").orElseThrow().getStatus());

        // 第二批设备各自恰好一条记录
        for (String id : List.of("dev-2", "dev-3")) {
            assertEquals(1L, recordRepo.findByCampaign("camp-keep").stream()
                    .filter(r -> r.getDeviceId().equals(id)).count());
        }
    }

    /**
     * 确定性交叉边界：
     * 等待确认时暂停/恢复被拒；确认后重复确认被拒；确认后暂停->恢复->暂停->恢复多次，
     * 再推进也只需确认一次，第二批只下发一次。
     */
    @Test
    void deterministicInterleavingOfConfirmPauseResumeAdvance() {
        for (int i = 0; i < 5; i++) {
            deviceService.register(new RegisterDeviceRequest("d-" + i, "linux-arm64", "1.0.0"));
        }
        campaignService.createCampaign(new CreateCampaignRequest("camp-mix", "2.0.0",
                List.of(List.of("d-0"), List.of("d-1", "d-2"), List.of("d-3", "d-4")),
                100.0, false, true));
        campaignService.advance("camp-mix");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "d-0", "camp-mix", 1L, true, "2.0.0", "linux-arm64"));
        assertEquals(CampaignStatus.AWAITING_CONFIRMATION,
                campaignRepo.findById("camp-mix").orElseThrow().getStatus());

        // 等待确认期间不允许暂停/恢复（三种停住状态不可互相混淆）
        assertRejected(() -> campaignService.pause("camp-mix"));
        assertRejected(() -> campaignService.resume("camp-mix"));
        // 未确认推进仍被拦截
        RejectedException noConfirm = assertThrows(RejectedException.class,
                () -> campaignService.advance("camp-mix"));
        assertEquals(RejectReason.ILLEGAL_CAMPAIGN_STATE, noConfirm.getReason());

        campaignService.confirmFirstBatch("camp-mix");

        // 确认后重复确认被拒
        RejectedException dup = assertThrows(RejectedException.class,
                () -> campaignService.confirmFirstBatch("camp-mix"));
        assertEquals(RejectReason.ILLEGAL_CAMPAIGN_STATE, dup.getReason());

        // 多轮暂停/恢复，确认始终有效
        for (int round = 0; round < 2; round++) {
            campaignService.pause("camp-mix");
            assertRejected(() -> campaignService.advance("camp-mix"));
            Campaign resumed = campaignService.resume("camp-mix");
            assertEquals(CampaignStatus.RUNNING, resumed.getStatus());
            assertRejected(() -> campaignService.confirmFirstBatch("camp-mix"));
        }

        campaignService.advance("camp-mix");
        // 推进后再次推进：本批尚未结清，被分批门禁拦截，且不会重复下发
        RejectedException reAdvance = assertThrows(RejectedException.class,
                () -> campaignService.advance("camp-mix"));
        assertEquals(RejectReason.BATCH_NOT_SETTLED, reAdvance.getReason());

        assertEquals(1, campaignRepo.findById("camp-mix").orElseThrow().getCurrentBatchIndex());
        for (String id : List.of("d-1", "d-2")) {
            assertEquals(1L, recordRepo.findByCampaign("camp-mix").stream()
                    .filter(r -> r.getDeviceId().equals(id)).count(),
                    "device " + id + " dispatched exactly once");
            assertEquals(DeviceStatus.UPGRADING, deviceRepo.findById(id).orElseThrow().getStatus());
        }
    }

    /** 未开启首批确认的活动：首批结清后保持 RUNNING，暂停/恢复/推进维持原行为，确认接口被拒 */
    @Test
    void noConfirmationFlagKeepsOriginalBehavior() {
        for (int i = 0; i < 3; i++) {
            deviceService.register(new RegisterDeviceRequest("n-" + i, "linux-arm64", "1.0.0"));
        }
        campaignService.createCampaign(new CreateCampaignRequest("camp-noconf", "2.0.0",
                List.of(List.of("n-0"), List.of("n-1", "n-2")), 100.0, false, false));
        campaignService.advance("camp-noconf");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "n-0", "camp-noconf", 1L, true, "2.0.0", "linux-arm64"));
        Campaign afterFirst = campaignRepo.findById("camp-noconf").orElseThrow();
        assertEquals(CampaignStatus.RUNNING, afterFirst.getStatus(),
                "without confirmation flag the campaign must not stop after first batch");

        // 没有确认点时调用确认接口必须被拒绝
        assertThrows(RejectedException.class,
                () -> campaignService.confirmFirstBatch("camp-noconf"));

        // 结清即放行：暂停/恢复后仍可直接推进
        campaignService.pause("camp-noconf");
        assertEquals(CampaignStatus.PAUSED,
                campaignRepo.findById("camp-noconf").orElseThrow().getStatus());
        campaignService.resume("camp-noconf");
        campaignService.advance("camp-noconf");
        assertEquals(1, campaignRepo.findById("camp-noconf").orElseThrow().getCurrentBatchIndex());
        assertEquals(DeviceStatus.UPGRADING, deviceRepo.findById("n-1").orElseThrow().getStatus());
    }

    private static void assertRejected(org.junit.jupiter.api.function.Executable op) {
        assertThrows(RejectedException.class, op);
    }

    /**
     * 确认与暂停、恢复、推进同时发生：在活动停于等待确认后，多线程随机混合调用四种操作。
     * 不变量：确认自始至终恰好成功一次；暂停后恢复永远不会回到等待确认；
     * 风暴期间第二批最多成功推进一次，风暴结束收敛后第二批设备仍各自只有一条下发记录。
     */
    @Test
    void concurrentMixedConfirmPauseResumeAdvanceKeepsSingleConfirmationAndDispatch() throws Exception {
        for (int i = 0; i < 4; i++) {
            deviceService.register(new RegisterDeviceRequest("x-" + i, "linux-arm64", "1.0.0"));
        }
        campaignService.createCampaign(new CreateCampaignRequest("camp-race", "2.0.0",
                List.of(List.of("x-0", "x-1"), List.of("x-2", "x-3")), 100.0, false, true));
        campaignService.advance("camp-race");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "x-0", "camp-race", 1L, true, "2.0.0", "linux-arm64"));
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "x-1", "camp-race", 1L, true, "2.0.0", "linux-arm64"));
        assertEquals(CampaignStatus.AWAITING_CONFIRMATION,
                campaignRepo.findById("camp-race").orElseThrow().getStatus());

        int threads = 24;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger confirmOk = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger pauseOk = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger resumeOk = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger advanceOk = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    java.util.Random rnd = new java.util.Random();
                    for (int i = 0; i < 200; i++) {
                        try {
                            switch (rnd.nextInt(4)) {
                                case 0 -> {
                                    campaignService.confirmFirstBatch("camp-race");
                                    confirmOk.incrementAndGet();
                                }
                                case 1 -> {
                                    campaignService.pause("camp-race");
                                    pauseOk.incrementAndGet();
                                }
                                case 2 -> {
                                    campaignService.resume("camp-race");
                                    resumeOk.incrementAndGet();
                                }
                                default -> {
                                    campaignService.advance("camp-race");
                                    advanceOk.incrementAndGet();
                                }
                            }
                        } catch (RejectedException ignored) {
                            // 状态不允许的交叉操作被拒绝即预期路径
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        Campaign after = campaignRepo.findById("camp-race").orElseThrow();
        log.info("[Test] storm results confirmOk={} pauseOk={} resumeOk={} advanceOk={} status={} confirmed={}",
                confirmOk.get(), pauseOk.get(), resumeOk.get(), advanceOk.get(),
                after.getStatus(), after.isFirstBatchConfirmed());

        // 核心不变量：无论暂停/恢复与确认如何交叉，确认只成功一次
        assertEquals(1, confirmOk.get(), "manual confirmation must take effect exactly once");
        assertTrue(after.isFirstBatchConfirmed());
        // 风暴期间第二批最多下发一次
        assertTrue(advanceOk.get() <= 1, "batch two may be advanced at most once, was " + advanceOk.get());

        // 收敛到可推进状态：若停在暂停，恢复后必须是 RUNNING 而非 AWAITING_CONFIRMATION
        if (after.getStatus() == CampaignStatus.PAUSED) {
            Campaign resumed = campaignService.resume("camp-race");
            assertEquals(CampaignStatus.RUNNING, resumed.getStatus(),
                    "resume must not bring back awaiting-confirmation");
        } else {
            assertEquals(CampaignStatus.RUNNING, after.getStatus());
        }

        // 若风暴中尚未推进，此时显式推进；无论如何第二批每台设备恰好一条记录
        if (campaignRepo.findById("camp-race").orElseThrow().getCurrentBatchIndex() < 1) {
            campaignService.advance("camp-race");
        }
        assertEquals(1, campaignRepo.findById("camp-race").orElseThrow().getCurrentBatchIndex());
        for (String id : List.of("x-2", "x-3")) {
            long count = recordRepo.findByCampaign("camp-race").stream()
                    .filter(r -> r.getDeviceId().equals(id)).count();
            assertEquals(1L, count, "device " + id + " must have exactly one dispatch record");
        }
    }
}
