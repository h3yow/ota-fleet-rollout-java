package com.github.h3yow.otafleetrolloutjava.tests;

import com.github.h3yow.otafleetrolloutjava.dto.CreateCampaignRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetrolloutjava.dto.ReportUpgradeRequest;
import com.github.h3yow.otafleetrolloutjava.dto.rehearsal.BatchPlan;
import com.github.h3yow.otafleetrolloutjava.dto.rehearsal.RehearsalReport;
import com.github.h3yow.otafleetrolloutjava.exception.RejectReason;
import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import com.github.h3yow.otafleetrolloutjava.model.Campaign;
import com.github.h3yow.otafleetrolloutjava.model.CampaignStatus;
import com.github.h3yow.otafleetrolloutjava.model.DeviceStatus;
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

/** 彩排无副作用、严格验收、分批门禁与首批等待确认 */
class ReleaseFlowTest {

    private static final Logger log = LoggerFactory.getLogger(ReleaseFlowTest.class);

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
    void rehearsalHasNoSideEffectsWhenFeasible() {
        deviceService.register(new RegisterDeviceRequest("dev-1", "linux-arm64", "1.0.0"));
        deviceService.register(new RegisterDeviceRequest("dev-2", "linux-arm64", "1.2.0"));
        CreateCampaignRequest plan = new CreateCampaignRequest(
                "camp-reh", "2.0.0",
                List.of(List.of("dev-1"), List.of("dev-2")), 50.0, true, true);

        log.info("[Test] input rehearsal plan=camp-reh batches=[[dev-1],[dev-2]] controlled=true confirmAfterFirst=true");
        RehearsalReport report = campaignService.rehearse(plan);
        log.info("[Test] decision feasible={} covered={}/{} blocked={} perBatch={}",
                report.feasible(), report.totalCoveredDevices(),
                report.totalPlannedDevices(), report.totalBlockedDevices(), report.batches());

        assertTrue(report.feasible());
        assertEquals(2, report.totalCoveredDevices());
        assertEquals(0, report.totalBlockedDevices());
        assertEquals(2, report.batches().size());
        assertEquals(1, report.batches().get(0).coveredDevices());
        assertEquals(1, report.batches().get(1).coveredDevices());

        // 关键判定：彩排不落任何真实数据
        assertTrue(campaignRepo.findAll().isEmpty(), "rehearsal must not create campaign");
        assertTrue(recordRepo.findByCampaign("camp-reh").isEmpty(), "rehearsal must not create records");
        assertEquals(0L, deviceRepo.findAll().stream()
                .filter(d -> d.getActiveCampaignId() != null).count(), "rehearsal must not occupy devices");
        assertEquals(DeviceStatus.IDLE, deviceRepo.findById("dev-1").orElseThrow().getStatus());
    }

    @Test
    void rehearsalDistinguishesBlockingReasons() {
        // 1) 存在设备；2) 平台不匹配；3) 源版本过低；4) 被其他进行中活动占用
        deviceService.register(new RegisterDeviceRequest("dev-ok", "linux-arm64", "1.0.0"));
        deviceService.register(new RegisterDeviceRequest("dev-win", "windows-x64", "1.0.0"));
        deviceService.register(new RegisterDeviceRequest("dev-low", "linux-arm64", "0.9.0"));
        deviceService.register(new RegisterDeviceRequest("dev-busy", "linux-arm64", "1.0.0"));
        // dev-busy 先被一个进行中活动占用
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-holder", "2.0.0", List.of(List.of("dev-busy")), 100.0));
        campaignService.advance("camp-holder");

        // 同批次内含：OK、平台不符、源版本低、被占用、不存在设备、以及重复出现的 dev-ok
        CreateCampaignRequest plan = new CreateCampaignRequest("camp-reh2", "2.0.0",
                List.of(List.of("dev-ok", "dev-win", "dev-low", "dev-busy", "dev-ghost", "dev-ok")),
                100.0);
        log.info("[Test] input rehearsal with ok/platform/low/busy/ghost/duplicate devices");

        RehearsalReport report = campaignService.rehearse(plan);
        BatchPlan batch = report.batches().get(0);
        log.info("[Test] decision feasible={} covered={} blocked={} reasons={}",
                report.feasible(), batch.coveredDevices(),
                batch.blockedDevices().size(),
                batch.blockedDevices().stream().map(b -> b.deviceId() + "=" + b.reason()).toList());

        assertFalse(report.feasible());
        assertEquals(1, batch.coveredDevices(), "only dev-ok is coverable");
        assertEquals(5, batch.blockedDevices().size());
        assertBlocked(batch, "dev-win", RejectReason.INCOMPATIBLE_FIRMWARE);
        assertBlocked(batch, "dev-low", RejectReason.VERSION_CONSTRAINT_VIOLATED);
        assertBlocked(batch, "dev-busy", RejectReason.DEVICE_BUSY_IN_OTHER_CAMPAIGN);
        assertBlocked(batch, "dev-ghost", RejectReason.DEVICE_NOT_FOUND);
        assertBlocked(batch, "dev-ok", RejectReason.DUPLICATE_DEVICE_IN_PLAN);

        // 彩排失败不留任何活动/占用/记录
        assertNull(campaignRepo.findById("camp-reh2").orElse(null));
        assertTrue(recordRepo.findByCampaign("camp-reh2").isEmpty());
        assertEquals("camp-holder",
                deviceRepo.findById("dev-busy").orElseThrow().getActiveCampaignId(),
                "pre-existing occupation must remain untouched");
    }

    private static void awaitQuietly(java.util.concurrent.CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (java.util.concurrent.BrokenBarrierException e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertBlocked(BatchPlan batch, String deviceId, RejectReason reason) {
        var match = batch.blockedDevices().stream()
                .filter(b -> b.deviceId().equals(deviceId)).findFirst().orElseThrow();
        assertEquals(reason.name(), match.reason(),
                "device " + deviceId + " expected reason " + reason);
    }

    @Test
    void strictAcceptanceVersionMismatchFailsAndRollsBack() {
        deviceService.register(new RegisterDeviceRequest("dev-strict", "linux-arm64", "1.5.0"));
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-ctrl", "2.0.0", List.of(List.of("dev-strict")), 100.0, true, false));
        campaignService.advance("camp-ctrl");

        log.info("[Test] input controlled campaign, device reports success but installedVersion=1.9.0, target=2.0.0");
        String result = deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-strict", "camp-ctrl", 1L, true, "1.9.0", "linux-arm64"));
        log.info("[Test] decision reportResult={} record={} deviceVersion={} deviceStatus={}",
                result,
                recordRepo.find("camp-ctrl", "dev-strict").orElseThrow().getStatus(),
                deviceRepo.findById("dev-strict").orElseThrow().getCurrentVersion(),
                deviceRepo.findById("dev-strict").orElseThrow().getStatus());

        // 不能当成成功放行：记录与设备均回到“已回滚到此前确认版本”的明确结论
        assertEquals(UpgradeStatus.ROLLED_BACK.name(), result);
        assertEquals(UpgradeStatus.ROLLED_BACK,
                recordRepo.find("camp-ctrl", "dev-strict").orElseThrow().getStatus());
        assertEquals(RejectReason.TARGET_VERSION_MISMATCH,
                recordRepo.find("camp-ctrl", "dev-strict").orElseThrow().getFailureReason());
        var device = deviceRepo.findById("dev-strict").orElseThrow();
        assertEquals("1.5.0", device.getCurrentVersion(),
                "must not stay on the untrusted version 1.9.0");
        assertEquals(DeviceStatus.ROLLED_BACK, device.getStatus());
        assertNull(device.getActiveCampaignId());

        // 全部批次结清 -> 活动完成
        assertEquals(CampaignStatus.COMPLETED,
                campaignRepo.findById("camp-ctrl").orElseThrow().getStatus());
    }

    @Test
    void nonControlledAcceptanceKeepsOriginalBehavior() {
        deviceService.register(new RegisterDeviceRequest("dev-len", "linux-arm64", "1.0.0"));
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-len", "2.0.0", List.of(List.of("dev-len")), 100.0, false, false));
        campaignService.advance("camp-len");

        log.info("[Test] input non-controlled campaign, success report carrying reportedVersion=2.0.0 (trusted)");
        String result = deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-len", "camp-len", 1L, true, "2.0.0", "linux-arm64"));
        assertEquals(UpgradeStatus.SUCCEEDED.name(), result);
        assertEquals("2.0.0", deviceRepo.findById("dev-len").orElseThrow().getCurrentVersion());
    }

    @Test
    void advanceRejectedWhilePreviousBatchNotSettled() {
        deviceService.register(new RegisterDeviceRequest("dev-a", "linux-arm64", "1.0.0"));
        deviceService.register(new RegisterDeviceRequest("dev-b", "linux-arm64", "1.0.0"));
        deviceService.register(new RegisterDeviceRequest("dev-c", "linux-arm64", "1.0.0"));
        campaignService.createCampaign(new CreateCampaignRequest("camp-gate", "2.0.0",
                List.of(List.of("dev-a", "dev-b"), List.of("dev-c")), 100.0));
        campaignService.advance("camp-gate");

        log.info("[Test] input: only dev-a reports, dev-b still pending -> advance should be rejected");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-a", "camp-gate", 1L, true, "2.0.0", "linux-arm64"));

        RejectedException ex = assertThrows(RejectedException.class,
                () -> campaignService.advance("camp-gate"));
        log.info("[Test] decision reason={} message={}", ex.getReason(), ex.getMessage());
        assertEquals(RejectReason.BATCH_NOT_SETTLED, ex.getReason());
        assertTrue(ex.getMessage().contains("dev-b"), "message should name pending device");
        // 下一批设备绝不能被提前下发
        assertEquals(DeviceStatus.IDLE, deviceRepo.findById("dev-c").orElseThrow().getStatus());
        assertTrue(recordRepo.find("camp-gate", "dev-c").isEmpty());

        // 上一批结清后才允许推进
        log.info("[Test] input: dev-b reports success -> advance now permitted");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-b", "camp-gate", 1L, true, "2.0.0", "linux-arm64"));
        Campaign advanced = campaignService.advance("camp-gate");
        assertEquals(1, advanced.getCurrentBatchIndex());
        assertEquals(DeviceStatus.UPGRADING, deviceRepo.findById("dev-c").orElseThrow().getStatus());
    }

    @Test
    void firstBatchWaitsForConfirmationAndStatesAreDistinct() {
        for (int i = 0; i < 4; i++) {
            deviceService.register(new RegisterDeviceRequest("dev-" + i, "linux-arm64", "1.0.0"));
        }
        campaignService.createCampaign(new CreateCampaignRequest("camp-conf", "2.0.0",
                List.of(List.of("dev-0", "dev-1"), List.of("dev-2", "dev-3")),
                100.0, false, true));
        campaignService.advance("camp-conf");

        // 首批结清前，推进被门禁拒绝
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-0", "camp-conf", 1L, true, "2.0.0", "linux-arm64"));
        assertThrows(RejectedException.class, () -> campaignService.advance("camp-conf"));

        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-1", "camp-conf", 1L, true, "2.0.0", "linux-arm64"));
        Campaign waiting = campaignRepo.findById("camp-conf").orElseThrow();
        log.info("[Test] decision after first batch settled: status={} autoPaused={}",
                waiting.getStatus(), waiting.isAutoPaused());
        // 首批结清后停在“等待确认”，后续批次未下发
        assertEquals(CampaignStatus.AWAITING_CONFIRMATION, waiting.getStatus());
        assertFalse(waiting.isAutoPaused());
        assertEquals(0, waiting.getCurrentBatchIndex());
        assertEquals(DeviceStatus.IDLE, deviceRepo.findById("dev-2").orElseThrow().getStatus());

        // 等待确认期间推进被拒绝
        RejectedException advanceBlocked = assertThrows(RejectedException.class,
                () -> campaignService.advance("camp-conf"));
        assertEquals(RejectReason.ILLEGAL_CAMPAIGN_STATE, advanceBlocked.getReason());

        // 人工确认后恢复 RUNNING，但仍需显式 advance 才下发
        Campaign confirmed = campaignService.confirmFirstBatch("camp-conf");
        assertEquals(CampaignStatus.RUNNING, confirmed.getStatus());

        // 重复确认被拒绝
        log.info("[Test] input: duplicate confirmation -> expect ILLEGAL_CAMPAIGN_STATE");
        RejectedException dup = assertThrows(RejectedException.class,
                () -> campaignService.confirmFirstBatch("camp-conf"));
        log.info("[Test] decision reason={} message={}", dup.getReason(), dup.getMessage());
        assertEquals(RejectReason.ILLEGAL_CAMPAIGN_STATE, dup.getReason());

        campaignService.advance("camp-conf");
        assertEquals(DeviceStatus.UPGRADING, deviceRepo.findById("dev-2").orElseThrow().getStatus());
    }

    @Test
    void awaitingConfirmationDiffersFromManualPauseAndFailureStop() {
        // 失败率触发停下：PAUSED + autoPaused=true
        deviceService.register(new RegisterDeviceRequest("dev-f", "linux-arm64", "1.0.0"));
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-stop", "2.0.0", List.of(List.of("dev-f"), List.of()), 0.0, false, true));
        campaignService.advance("camp-stop");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-f", "camp-stop", 1L, false, "1.0.0", "linux-arm64"));
        Campaign stopped = campaignRepo.findById("camp-stop").orElseThrow();
        log.info("[Test] failure-stop: status={} autoPaused={}", stopped.getStatus(), stopped.isAutoPaused());
        assertEquals(CampaignStatus.PAUSED, stopped.getStatus());
        assertTrue(stopped.isAutoPaused(), "failure threshold stop must be PAUSED+autoPaused, not awaiting-confirmation");

        // 手动暂停：PAUSED + autoPaused=false
        deviceService.register(new RegisterDeviceRequest("dev-p", "linux-arm64", "1.0.0"));
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-pause", "2.0.0", List.of(List.of("dev-p")), 100.0));
        campaignService.advance("camp-pause");
        campaignService.pause("camp-pause");
        Campaign paused = campaignRepo.findById("camp-pause").orElseThrow();
        assertEquals(CampaignStatus.PAUSED, paused.getStatus());
        assertFalse(paused.isAutoPaused(), "manual pause must be PAUSED with autoPaused=false");
    }

    @Test
    void firstBatchConfirmationSurvivesPauseAndResume() {
        deviceService.register(new RegisterDeviceRequest("dev-pa0", "linux-arm64", "1.0.0"));
        deviceService.register(new RegisterDeviceRequest("dev-pa1", "linux-arm64", "1.0.0"));
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-pa", "2.0.0",
                List.of(List.of("dev-pa0"), List.of("dev-pa1")), 100.0, false, true));
        campaignService.advance("camp-pa");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-pa0", "camp-pa", 1L, true, "2.0.0", "linux-arm64"));
        assertEquals(CampaignStatus.AWAITING_CONFIRMATION,
                campaignRepo.findById("camp-pa").orElseThrow().getStatus());

        // 人工确认通过
        campaignService.confirmFirstBatch("camp-pa");
        Campaign confirmed = campaignRepo.findById("camp-pa").orElseThrow();
        assertEquals(CampaignStatus.RUNNING, confirmed.getStatus());
        assertTrue(confirmed.isFirstBatchConfirmed(), "confirmation must be recorded on the campaign");

        // 确认后、推进前，有人手动暂停
        campaignService.pause("camp-pa");
        Campaign paused = campaignRepo.findById("camp-pa").orElseThrow();
        assertEquals(CampaignStatus.PAUSED, paused.getStatus());
        assertFalse(paused.isAutoPaused(), "manual pause must not be mistaken for failure stop");
        // 关键回归点：暂停不能抹掉确认结论
        assertTrue(paused.isFirstBatchConfirmed(), "pause must not clear the first-batch confirmation");

        // 暂停期间推进仍被明确拦截
        RejectedException blockedWhilePaused = assertThrows(RejectedException.class,
                () -> campaignService.advance("camp-pa"));
        assertEquals(RejectReason.ILLEGAL_CAMPAIGN_STATE, blockedWhilePaused.getReason());
        assertTrue(blockedWhilePaused.getMessage().contains("paused"));

        // 恢复：必须回到 RUNNING 而不是 AWAITING_CONFIRMATION
        campaignService.resume("camp-pa");
        Campaign resumed = campaignRepo.findById("camp-pa").orElseThrow();
        log.info("[Test] after resume: status={} confirmed={} batchIndex={}",
                resumed.getStatus(), resumed.isFirstBatchConfirmed(), resumed.getCurrentBatchIndex());
        assertEquals(CampaignStatus.RUNNING, resumed.getStatus(),
                "resume must not send a confirmed campaign back to awaiting-confirmation");
        assertTrue(resumed.isFirstBatchConfirmed());

        // 恢复后重复确认仍被拒绝（确认点已经消费过）
        RejectedException reconfirm = assertThrows(RejectedException.class,
                () -> campaignService.confirmFirstBatch("camp-pa"));
        assertEquals(RejectReason.ILLEGAL_CAMPAIGN_STATE, reconfirm.getReason());

        // 恢复后可直接推进第二批，且只下发一次
        campaignService.advance("camp-pa");
        assertEquals(1, campaignRepo.findById("camp-pa").orElseThrow().getCurrentBatchIndex());
        assertEquals(DeviceStatus.UPGRADING,
                deviceRepo.findById("dev-pa1").orElseThrow().getStatus());
        assertEquals(1, recordRepo.findByCampaign("camp-pa").stream()
                .filter(r -> r.getDeviceId().equals("dev-pa1")).count(),
                "second-batch device must have exactly one dispatch record");
        assertEquals(1, recordRepo.findByCampaign("camp-pa").stream()
                .filter(r -> r.getDeviceId().equals("dev-pa0")).count(),
                "first-batch device must still have exactly one record");

        // 再暂停/恢复一次（第二批在途），确认结论依旧有效，状态不回退
        campaignService.pause("camp-pa");
        campaignService.resume("camp-pa");
        assertEquals(CampaignStatus.RUNNING,
                campaignRepo.findById("camp-pa").orElseThrow().getStatus());
    }

    @Test
    void confirmationInterleavedWithPauseResumeAndAdvanceDispatchesOnce() throws Exception {
        deviceService.register(new RegisterDeviceRequest("dev-ix0", "linux-arm64", "1.0.0"));
        deviceService.register(new RegisterDeviceRequest("dev-ix1", "linux-arm64", "1.0.0"));
        campaignService.createCampaign(new CreateCampaignRequest(
                "camp-ix", "2.0.0",
                List.of(List.of("dev-ix0"), List.of("dev-ix1")), 100.0, false, true));
        campaignService.advance("camp-ix");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-ix0", "camp-ix", 1L, true, "2.0.0", "linux-arm64"));
        assertEquals(CampaignStatus.AWAITING_CONFIRMATION,
                campaignRepo.findById("camp-ix").orElseThrow().getStatus());

        int confirmThreads = 8;
        int advanceThreads = 8;
        int flickerThreads = 4;
        int iterations = 50;
        java.util.concurrent.atomic.AtomicInteger confirmedOk = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger advancedOk = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CyclicBarrier barrier =
                new java.util.concurrent.CyclicBarrier(confirmThreads + advanceThreads + flickerThreads);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(confirmThreads + advanceThreads + flickerThreads);
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        // 确认者：重复/并发确认，最终只能有一次成功
        for (int i = 0; i < confirmThreads; i++) {
            futures.add(pool.submit(() -> {
                awaitQuietly(barrier);
                for (int k = 0; k < iterations; k++) {
                    try {
                        campaignService.confirmFirstBatch("camp-ix");
                        confirmedOk.incrementAndGet();
                    } catch (RejectedException ignored) {
                        // 未到确认点 / 确认点已被他人消费：预期拒绝
                    }
                }
            }));
        }
        // 推进者：与确认/暂停/恢复交叉，尝试推进第二批
        for (int i = 0; i < advanceThreads; i++) {
            futures.add(pool.submit(() -> {
                awaitQuietly(barrier);
                for (int k = 0; k < iterations; k++) {
                    try {
                        campaignService.advance("camp-ix");
                        advancedOk.incrementAndGet();
                    } catch (RejectedException ignored) {
                        // 等待确认/暂停中/无更多批次：预期拒绝
                    }
                }
            }));
        }
        // 暂停/恢复抖动者：随时打断，验证确认结论不被抹掉
        for (int i = 0; i < flickerThreads; i++) {
            futures.add(pool.submit(() -> {
                awaitQuietly(barrier);
                for (int k = 0; k < iterations; k++) {
                    try {
                        campaignService.pause("camp-ix");
                    } catch (RejectedException ignored) {
                        // 非 RUNNING 时暂停被拒绝：预期
                    }
                    try {
                        campaignService.resume("camp-ix");
                    } catch (RejectedException ignored) {
                        // 非 PAUSED 时恢复被拒绝：预期
                    }
                }
            }));
        }
        for (var f : futures) {
            f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        log.info("[Test] interleaved phase: confirmedOk={} advancedOk={} status={} batchIndex={}",
                confirmedOk.get(), advancedOk.get(),
                campaignRepo.findById("camp-ix").orElseThrow().getStatus(),
                campaignRepo.findById("camp-ix").orElseThrow().getCurrentBatchIndex());

        // 无论交叉顺序如何，确认只能生效一次
        assertEquals(1, confirmedOk.get(), "confirmation must take effect exactly once under concurrency");

        // 交叉阶段结束后在主线程把状态归一化，再推进到最终批次；全程成功推进总数必须恰好 1
        Campaign campaign = campaignRepo.findById("camp-ix").orElseThrow();
        if (campaign.getStatus() == CampaignStatus.PAUSED) {
            campaignService.resume("camp-ix");
            campaign = campaignRepo.findById("camp-ix").orElseThrow();
        }
        if (campaign.getCurrentBatchIndex() < 1) {
            // 交叉阶段第二批尚未下发：此时绝不能还停在等待确认
            assertEquals(CampaignStatus.RUNNING, campaign.getStatus(),
                    "confirmed campaign must never return to awaiting-confirmation after pause/resume");
            campaignService.advance("camp-ix");
            advancedOk.incrementAndGet();
        }

        campaign = campaignRepo.findById("camp-ix").orElseThrow();
        assertEquals(1, campaign.getCurrentBatchIndex());
        assertEquals(CampaignStatus.RUNNING, campaign.getStatus());
        assertEquals(1, advancedOk.get(), "second batch must dispatch exactly once across all threads");
        assertEquals(1, recordRepo.findByCampaign("camp-ix").stream()
                .filter(r -> r.getDeviceId().equals("dev-ix1")).count(),
                "no duplicate dispatch records under interleaved confirm/pause/resume/advance");
        assertEquals(1, recordRepo.findByCampaign("camp-ix").stream()
                .filter(r -> r.getDeviceId().equals("dev-ix0")).count());
        assertEquals(DeviceStatus.UPGRADING,
                deviceRepo.findById("dev-ix1").orElseThrow().getStatus());
    }

    @Test
    void concurrentConfirmationsReleaseOnlyOnce() throws Exception {
        deviceService.register(new RegisterDeviceRequest("dev-cc", "linux-arm64", "1.0.0"));
        deviceService.register(new RegisterDeviceRequest("dev-cc2", "linux-arm64", "1.0.0"));
        campaignService.createCampaign(new CreateCampaignRequest("camp-cc", "2.0.0",
                List.of(List.of("dev-cc"), List.of("dev-cc2")), 100.0, false, true));
        campaignService.advance("camp-cc");
        deviceService.reportUpgradeResult(new ReportUpgradeRequest(
                "dev-cc", "camp-cc", 1L, true, "2.0.0", "linux-arm64"));
        assertEquals(CampaignStatus.AWAITING_CONFIRMATION,
                campaignRepo.findById("camp-cc").orElseThrow().getStatus());

        int threads = 24;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger confirmed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    campaignService.confirmFirstBatch("camp-cc");
                    confirmed.incrementAndGet();
                } catch (RejectedException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();
        log.info("[Test] concurrent confirmation: threads={} confirmed={} rejected={} finalStatus={}",
                threads, confirmed.get(), rejected.get(),
                campaignRepo.findById("camp-cc").orElseThrow().getStatus());
        // 判定依据：同一确认点并发确认，恰好一次生效
        assertEquals(1, confirmed.get());
        assertEquals(threads - 1, rejected.get());
        assertEquals(CampaignStatus.RUNNING,
                campaignRepo.findById("camp-cc").orElseThrow().getStatus());

        // 确认后下一批也只能下发一次：并发 advance，只有一个线程能推进
        java.util.concurrent.ExecutorService pool2 =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start2 = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger advanced = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger advanceRejected = new java.util.concurrent.atomic.AtomicInteger();
        List<java.util.concurrent.Future<?>> f2 = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            f2.add(pool2.submit(() -> {
                try {
                    start2.await();
                    campaignService.advance("camp-cc");
                    advanced.incrementAndGet();
                } catch (RejectedException e) {
                    advanceRejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start2.countDown();
        for (var f : f2) {
            f.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool2.shutdown();
        log.info("[Test] concurrent advance after confirmation: advanced={} rejected={}",
                advanced.get(), advanceRejected.get());
        assertEquals(1, advanced.get(), "batch two must be dispatched exactly once");
        assertEquals(1, recordRepo.findByCampaign("camp-cc").stream()
                .filter(r -> r.getDeviceId().equals("dev-cc2")).count());
    }
}
