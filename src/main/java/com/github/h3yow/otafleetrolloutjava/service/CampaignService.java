package com.github.h3yow.otafleetrolloutjava.service;

import com.github.h3yow.otafleetrolloutjava.dto.BatchView;
import com.github.h3yow.otafleetrolloutjava.dto.CampaignStats;
import com.github.h3yow.otafleetrolloutjava.dto.CampaignView;
import com.github.h3yow.otafleetrolloutjava.dto.CreateCampaignRequest;
import com.github.h3yow.otafleetrolloutjava.dto.ReportResult;
import com.github.h3yow.otafleetrolloutjava.dto.TaskView;
import com.github.h3yow.otafleetrolloutjava.dto.UpgradeReportRequest;
import com.github.h3yow.otafleetrolloutjava.exception.NotFoundException;
import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import com.github.h3yow.otafleetrolloutjava.model.BatchStatus;
import com.github.h3yow.otafleetrolloutjava.model.CampaignBatch;
import com.github.h3yow.otafleetrolloutjava.model.CampaignStatus;
import com.github.h3yow.otafleetrolloutjava.model.Device;
import com.github.h3yow.otafleetrolloutjava.model.Firmware;
import com.github.h3yow.otafleetrolloutjava.model.RejectReason;
import com.github.h3yow.otafleetrolloutjava.model.RolloutCampaign;
import com.github.h3yow.otafleetrolloutjava.model.TaskStatus;
import com.github.h3yow.otafleetrolloutjava.model.UpgradeTask;
import com.github.h3yow.otafleetrolloutjava.store.FleetStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class CampaignService {

    private static final Logger log = LoggerFactory.getLogger(CampaignService.class);

    private final FleetStore store;

    public CampaignService(FleetStore store) {
        this.store = store;
    }

    /** 创建灰度活动：校验固件兼容约束，并保证同一设备同一时刻只属于一个进行中的活动。 */
    public CampaignView create(CreateCampaignRequest req) {
        if (req == null) {
            throw new RejectedException(RejectReason.INVALID_REQUEST, "request body is required");
        }
        if (DeviceService.isBlank(req.getCampaignId())) {
            throw new RejectedException(RejectReason.INVALID_REQUEST, "campaignId is required");
        }
        if (DeviceService.isBlank(req.getFirmwareId())) {
            throw new RejectedException(RejectReason.INVALID_REQUEST, "firmwareId is required");
        }
        if (req.getBatches() == null || req.getBatches().isEmpty()
                || req.getBatches().stream().allMatch(b -> b == null || b.isEmpty())) {
            throw new RejectedException(RejectReason.INVALID_REQUEST, "at least one non-empty batch is required");
        }
        double threshold = req.getFailureRateThreshold() == null ? 0.5 : req.getFailureRateThreshold();
        if (threshold < 0 || threshold > 1) {
            throw new RejectedException(RejectReason.INVALID_REQUEST,
                    "failureRateThreshold must be within [0,1], actual=" + threshold);
        }

        store.writeLock().lock();
        try {
            if (store.campaigns().containsKey(req.getCampaignId().trim())) {
                throw new RejectedException(RejectReason.INVALID_REQUEST,
                        "campaign already exists: " + req.getCampaignId());
            }
            Firmware fw = store.firmwares().get(req.getFirmwareId().trim());
            if (fw == null) {
                throw new NotFoundException(RejectReason.FIRMWARE_NOT_FOUND,
                        "firmware not found: " + req.getFirmwareId());
            }

            RolloutCampaign campaign = new RolloutCampaign();
            campaign.setCampaignId(req.getCampaignId().trim());
            campaign.setFirmwareId(fw.getFirmwareId());
            campaign.setTargetVersion(fw.getVersion());
            campaign.setPlatform(fw.getPlatform());
            campaign.setFailureRateThreshold(threshold);
            campaign.setStatus(CampaignStatus.PENDING);
            long now = System.currentTimeMillis();
            campaign.setCreatedAt(now);
            campaign.setUpdatedAt(now);

            Set<String> seenInCampaign = new HashSet<>();
            int idx = 0;
            for (List<String> batchDevices : req.getBatches()) {
                List<String> ids = new ArrayList<>();
                if (batchDevices != null) {
                    for (String raw : batchDevices) {
                        String deviceId = raw == null ? null : raw.trim();
                        if (DeviceService.isBlank(deviceId)) {
                            throw new RejectedException(RejectReason.INVALID_REQUEST,
                                    "blank deviceId in batch " + idx);
                        }
                        Device device = store.devices().get(deviceId);
                        if (device == null) {
                            throw new NotFoundException(RejectReason.DEVICE_NOT_FOUND,
                                    "device not found: " + deviceId);
                        }
                        if (!seenInCampaign.add(deviceId)) {
                            throw new RejectedException(RejectReason.INVALID_REQUEST,
                                    "device " + deviceId + " appears in multiple batches of campaign "
                                            + campaign.getCampaignId());
                        }
                        ensureDeviceFree(deviceId);
                        ensureCompatible(campaign.getCampaignId(), device, fw);
                        ids.add(deviceId);
                        store.tasks().put(FleetStore.taskKey(campaign.getCampaignId(), deviceId),
                                new UpgradeTask(campaign.getCampaignId(), idx, deviceId, fw.getVersion()));
                    }
                }
                CampaignBatch batch = new CampaignBatch(idx, ids);
                campaign.getBatches().add(batch);
                idx++;
            }

            store.campaigns().put(campaign.getCampaignId(), campaign);
            store.persist();
            log.info("campaign created: campaignId={}, firmware={}, target={}, threshold={}, batches={}",
                    campaign.getCampaignId(), fw.getFirmwareId(), fw.getVersion(), threshold,
                    campaign.getBatches().size());
            return toView(campaign);
        } finally {
            store.writeLock().unlock();
        }
    }

    /** 设备当前是否已属于某个 PENDING/RUNNING/PAUSED 的活动。 */
    private void ensureDeviceFree(String deviceId) {
        for (UpgradeTask t : store.tasks().values()) {
            if (!t.getDeviceId().equals(deviceId)) {
                continue;
            }
            RolloutCampaign other = store.campaigns().get(t.getCampaignId());
            if (other != null && other.getStatus() != CampaignStatus.COMPLETED
                    && other.getStatus() != CampaignStatus.TERMINATED) {
                throw new RejectedException(RejectReason.DEVICE_IN_ANOTHER_CAMPAIGN,
                        "device " + deviceId + " already in active campaign " + t.getCampaignId()
                                + " (status=" + other.getStatus() + ")");
            }
        }
    }

    /** 固件兼容约束：平台必须兼容、设备当前版本不得低于最低基线版本。 */
    private void ensureCompatible(String campaignId, Device device, Firmware fw) {
        boolean platformOk;
        if (fw.getCompatiblePlatforms() == null || fw.getCompatiblePlatforms().isEmpty()) {
            platformOk = fw.getPlatform().equals(device.getPlatform());
        } else {
            platformOk = fw.getCompatiblePlatforms().contains(device.getPlatform());
        }
        if (!platformOk) {
            log.warn("compatibility rejected: campaign={}, device={}, devicePlatform={}, firmwarePlatform={}, compatible={}",
                    campaignId, device.getDeviceId(), device.getPlatform(), fw.getPlatform(),
                    fw.getCompatiblePlatforms());
            throw new RejectedException(RejectReason.FIRMWARE_PLATFORM_UNSUPPORTED,
                    "device platform " + device.getPlatform() + " not supported by firmware "
                            + fw.getFirmwareId() + " (platform=" + fw.getPlatform()
                            + ", compatible=" + fw.getCompatiblePlatforms() + ")");
        }
        if (!VersionSupport.gte(device.getCurrentVersion(), fw.getMinBaseVersion())) {
            log.warn("compatibility rejected: campaign={}, device={}, current={}, minBase={}",
                    campaignId, device.getDeviceId(), device.getCurrentVersion(), fw.getMinBaseVersion());
            throw new RejectedException(RejectReason.FIRMWARE_BASE_VERSION_TOO_OLD,
                    "device version " + device.getCurrentVersion() + " is below minBaseVersion "
                            + fw.getMinBaseVersion() + " for firmware " + fw.getFirmwareId());
        }
    }

    /** 激活下一个批次：当前批次未全部终态或活动未处于可推进状态时拒绝。 */
    public CampaignView activateNextBatch(String campaignId) {
        store.writeLock().lock();
        try {
            RolloutCampaign campaign = requireCampaign(campaignId);
            if (campaign.getStatus() == CampaignStatus.TERMINATED) {
                throw new RejectedException(RejectReason.CAMPAIGN_TERMINATED,
                        "campaign terminated: " + campaignId);
            }
            if (campaign.getStatus() == CampaignStatus.COMPLETED) {
                throw new RejectedException(RejectReason.NO_MORE_BATCHES,
                        "campaign already completed: " + campaignId);
            }
            if (campaign.getStatus() == CampaignStatus.PAUSED) {
                throw new RejectedException(RejectReason.CAMPAIGN_NOT_RUNNABLE,
                        "campaign is paused (" + campaign.getPauseReason()
                                + "), resume before advancing: " + campaignId);
            }
            int active = campaign.getActiveBatchIndex();
            if (active >= 0 && !isBatchSettled(campaign, active)) {
                throw new RejectedException(RejectReason.CAMPAIGN_NOT_RUNNABLE,
                        "batch " + active + " has unsettled tasks, cannot advance");
            }
            int next = active + 1;
            if (next >= campaign.getBatches().size()) {
                // 所有批次均已终态 -> 活动完成
                if (active >= 0 && isBatchSettled(campaign, active)) {
                    campaign.setStatus(CampaignStatus.COMPLETED);
                    campaign.setUpdatedAt(System.currentTimeMillis());
                    store.persist();
                    log.info("campaign completed naturally: campaignId={}", campaignId);
                    return toView(campaign);
                }
                throw new RejectedException(RejectReason.NO_MORE_BATCHES,
                        "no more batches in campaign " + campaignId);
            }
            CampaignBatch batch = campaign.getBatches().get(next);
            batch.setStatus(BatchStatus.ACTIVE);
            batch.setActivatedAt(System.currentTimeMillis());
            for (String deviceId : batch.getDeviceIds()) {
                UpgradeTask task = store.tasks().get(FleetStore.taskKey(campaignId, deviceId));
                if (task != null && task.getStatus() == TaskStatus.ASSIGNED) {
                    task.setStatus(TaskStatus.DISPATCHED);
                    task.setDispatchedAt(System.currentTimeMillis());
                }
            }
            campaign.setActiveBatchIndex(next);
            campaign.setStatus(CampaignStatus.RUNNING);
            campaign.setPauseReason(null);
            campaign.setUpdatedAt(System.currentTimeMillis());
            store.persist();
            log.info("batch activated: campaignId={}, batchIndex={}, devices={}",
                    campaignId, next, batch.getDeviceIds().size());
            return toView(campaign);
        } finally {
            store.writeLock().unlock();
        }
    }

    public CampaignView pause(String campaignId) {
        store.writeLock().lock();
        try {
            RolloutCampaign campaign = requireCampaign(campaignId);
            switch (campaign.getStatus()) {
                case TERMINATED -> throw new RejectedException(RejectReason.CAMPAIGN_TERMINATED,
                        "cannot pause terminated campaign: " + campaignId);
                case COMPLETED -> throw new RejectedException(RejectReason.CAMPAIGN_NOT_RUNNABLE,
                        "cannot pause completed campaign: " + campaignId);
                case PAUSED -> throw new RejectedException(RejectReason.CAMPAIGN_ALREADY_PAUSED,
                        "campaign already paused (" + campaign.getPauseReason() + "): " + campaignId);
                default -> {
                    campaign.setStatus(CampaignStatus.PAUSED);
                    campaign.setPauseReason("MANUAL");
                    campaign.setUpdatedAt(System.currentTimeMillis());
                    store.persist();
                    log.info("campaign paused manually: campaignId={}", campaignId);
                }
            }
            return toView(campaign);
        } finally {
            store.writeLock().unlock();
        }
    }

    public CampaignView resume(String campaignId) {
        store.writeLock().lock();
        try {
            RolloutCampaign campaign = requireCampaign(campaignId);
            switch (campaign.getStatus()) {
                case TERMINATED -> throw new RejectedException(RejectReason.CAMPAIGN_TERMINATED,
                        "cannot resume terminated campaign: " + campaignId);
                case COMPLETED -> throw new RejectedException(RejectReason.CAMPAIGN_NOT_RUNNABLE,
                        "cannot resume completed campaign: " + campaignId);
                case RUNNING -> log.info("campaign resume idempotent: campaignId={}", campaignId);
                default -> {
                    campaign.setStatus(CampaignStatus.RUNNING);
                    log.info("campaign resumed: campaignId={}, previousPauseReason={}",
                            campaignId, campaign.getPauseReason());
                    campaign.setPauseReason(null);
                    campaign.setUpdatedAt(System.currentTimeMillis());
                    store.persist();
                }
            }
            return toView(campaign);
        } finally {
            store.writeLock().unlock();
        }
    }

    public CampaignView terminate(String campaignId) {
        store.writeLock().lock();
        try {
            RolloutCampaign campaign = requireCampaign(campaignId);
            if (campaign.getStatus() == CampaignStatus.TERMINATED) {
                return toView(campaign);
            }
            if (campaign.getStatus() == CampaignStatus.COMPLETED) {
                throw new RejectedException(RejectReason.CAMPAIGN_NOT_RUNNABLE,
                        "cannot terminate completed campaign: " + campaignId);
            }
            campaign.setStatus(CampaignStatus.TERMINATED);
            campaign.setUpdatedAt(System.currentTimeMillis());
            store.persist();
            log.info("campaign terminated: campaignId={}", campaignId);
            return toView(campaign);
        } finally {
            store.writeLock().unlock();
        }
    }

    public CampaignView get(String campaignId) {
        store.readLock().lock();
        try {
            return toView(requireCampaign(campaignId));
        } finally {
            store.readLock().unlock();
        }
    }

    /**
     * 升级结果上报（核心幂等与状态机）：
     * 1) 重复(seq 相同)/乱序或迟到(seq 更小) 上报被拒绝，不增加 attempts、不回退状态；
     * 2) 平台缺失/不一致、版本缺失、上报版本与目标不一致均给出可区分原因；
     * 3) FAILURE 或校验不通过 -> 回滚到最近一次确认可用版本(LKG)；SUCCESS 且版本一致 -> 更新 LKG；
     * 4) 批次终态后自动收尾；整活动失败率达到阈值 -> 自动暂停，阻止继续下发。
     */
    public ReportResult report(UpgradeReportRequest req) {
        if (req == null) {
            throw new RejectedException(RejectReason.INVALID_REQUEST, "request body is required");
        }
        if (DeviceService.isBlank(req.getDeviceId()) || DeviceService.isBlank(req.getCampaignId())) {
            throw new RejectedException(RejectReason.INVALID_REQUEST, "deviceId and campaignId are required");
        }
        if (req.getSeq() == null) {
            throw new RejectedException(RejectReason.INVALID_REQUEST, "seq is required for ordering");
        }
        if (req.getOutcome() == null) {
            throw new RejectedException(RejectReason.INVALID_REQUEST, "outcome is required (SUCCESS/FAILURE)");
        }
        if (DeviceService.isBlank(req.getPlatform())) {
            throw new RejectedException(RejectReason.MISSING_PLATFORM,
                    "platform is missing in report from device " + req.getDeviceId());
        }
        if (DeviceService.isBlank(req.getReportedVersion())) {
            throw new RejectedException(RejectReason.MISSING_VERSION,
                    "reportedVersion is missing in report from device " + req.getDeviceId());
        }

        store.writeLock().lock();
        try {
            String deviceId = req.getDeviceId().trim();
            String campaignId = req.getCampaignId().trim();
            long seq = req.getSeq();

            Device device = store.devices().get(deviceId);
            if (device == null) {
                throw new NotFoundException(RejectReason.DEVICE_NOT_FOUND, "device not found: " + deviceId);
            }
            RolloutCampaign campaign = store.campaigns().get(campaignId);
            if (campaign == null) {
                throw new NotFoundException(RejectReason.CAMPAIGN_NOT_FOUND,
                        "campaign not found: " + campaignId);
            }
            UpgradeTask task = store.tasks().get(FleetStore.taskKey(campaignId, deviceId));
            if (task == null || task.getStatus() == TaskStatus.ASSIGNED) {
                throw new RejectedException(RejectReason.NO_ACTIVE_TASK,
                        "no dispatched upgrade task for device " + deviceId + " in campaign " + campaignId);
            }
            if (!device.getPlatform().equals(req.getPlatform().trim())) {
                log.warn("report rejected: deviceId={}, registeredPlatform={}, reportedPlatform={}",
                        deviceId, device.getPlatform(), req.getPlatform());
                throw new RejectedException(RejectReason.PLATFORM_MISMATCH,
                        "platform mismatch: registered=" + device.getPlatform()
                                + ", reported=" + req.getPlatform());
            }

            ReportResult result = new ReportResult();
            result.setAttempts(task.getAttempts());
            result.setTaskStatus(task.getStatus());

            // 幂等/乱序判定：seq 相等=重复；seq 更小=乱序/迟到（即使任务已终态也保持状态）；
            // 终态后出现更大 seq 视为对同一升级结论的重复上报。
            if (task.getAttempts() > 0 && seq == task.getLastReportSeq()) {
                result.setAccepted(false);
                result.setRejectReason(RejectReason.DUPLICATE_REPORT.name());
                log.info("duplicate report same seq: campaign={}, device={}, seq={}", campaignId, deviceId, seq);
                return result;
            }
            if (task.getAttempts() > 0 && seq < task.getLastReportSeq()) {
                result.setAccepted(false);
                result.setRejectReason(RejectReason.STALE_REPORT.name());
                log.info("stale/out-of-order report: campaign={}, device={}, seq={}, lastAcceptedSeq={}",
                        campaignId, deviceId, seq, task.getLastReportSeq());
                return result;
            }
            if (task.isTerminal()) {
                result.setAccepted(false);
                result.setRejectReason(RejectReason.DUPLICATE_REPORT.name());
                log.info("duplicate report on terminal task: campaign={}, device={}, seq={}, status={}",
                        campaignId, deviceId, seq, task.getStatus());
                return result;
            }

            // 接受该上报：一次升级尝试只计一次
            task.setAttempts(task.getAttempts() + 1);
            task.setLastReportSeq(seq);
            task.setReportedVersion(req.getReportedVersion().trim());

            boolean validationFailed = false;
            RejectReason failReason = null;
            if (req.getOutcome() == com.github.h3yow.otafleetrolloutjava.model.ReportOutcome.FAILURE) {
                validationFailed = true;
                failReason = RejectReason.INVALID_REQUEST; // 设备自报失败
                log.info("device reported FAILURE: campaign={}, device={}, seq={}", campaignId, deviceId, seq);
            } else if (!req.getReportedVersion().trim().equals(task.getTargetVersion())) {
                validationFailed = true;
                failReason = RejectReason.TARGET_VERSION_MISMATCH;
                log.warn("target version mismatch: campaign={}, device={}, expected={}, reported={}",
                        campaignId, deviceId, task.getTargetVersion(), req.getReportedVersion());
            }

            if (validationFailed) {
                String lkg = device.getLastKnownGoodVersion();
                if (lkg == null || lkg.isBlank()) {
                    task.setStatus(TaskStatus.FAILED);
                    task.setFailureReason("NO_KNOWN_GOOD_BASELINE");
                    log.error("rollback impossible, no known-good baseline: campaign={}, device={}", campaignId, deviceId);
                } else {
                    device.setCurrentVersion(lkg);
                    task.setStatus(TaskStatus.ROLLED_BACK);
                    task.setRollbackToVersion(lkg);
                    task.setFailureReason(failReason.name());
                    result.setRollbackToVersion(lkg);
                    log.info("rolled back: campaign={}, device={}, from={}, toLkg={}",
                            campaignId, deviceId, req.getReportedVersion(), lkg);
                }
            } else {
                device.setCurrentVersion(task.getTargetVersion());
                device.setLastKnownGoodVersion(task.getTargetVersion()); // 确认可用，更新回滚基线
                task.setStatus(TaskStatus.SUCCESS);
                log.info("upgrade success: campaign={}, device={}, version={}, seq={}",
                        campaignId, deviceId, task.getTargetVersion(), seq);
            }
            task.setCompletedAt(System.currentTimeMillis());

            // 批次收尾
            int active = campaign.getActiveBatchIndex();
            if (active == task.getBatchIndex() && isBatchSettled(campaign, active)) {
                campaign.getBatches().get(active).setStatus(BatchStatus.COMPLETED);
                log.info("batch settled: campaign={}, batch={}", campaignId, active);
            }

            // 失败率自动暂停（仅对进行中活动；最后一批已全部终态则直接完成）
            boolean autoPaused = false;
            CampaignStats stats = buildStats(campaign);
            if (campaign.getStatus() == CampaignStatus.RUNNING
                    && stats.getFailureRate() >= campaign.getFailureRateThreshold()
                    && stats.getTotal() > 0 && settledCount(campaign) > 0) {
                campaign.setStatus(CampaignStatus.PAUSED);
                campaign.setPauseReason("AUTO_FAILURE_RATE");
                autoPaused = true;
                log.warn("auto paused by failure rate: campaign={}, failureRate={}, threshold={}, success={}, rolledBack={}, failed={}",
                        campaignId, stats.getFailureRate(), campaign.getFailureRateThreshold(),
                        stats.getSuccess(), stats.getRolledBack(), stats.getFailed());
            }
            if (active >= 0 && isBatchSettled(campaign, active)
                    && active == campaign.getBatches().size() - 1) {
                campaign.setStatus(CampaignStatus.COMPLETED);
                campaign.setPauseReason(null);
                autoPaused = false;
                log.info("campaign completed after last batch: campaign={}", campaignId);
            }
            campaign.setUpdatedAt(System.currentTimeMillis());
            store.persist();

            result.setAccepted(true);
            result.setAttempts(task.getAttempts());
            result.setTaskStatus(task.getStatus());
            result.setCampaignAutoPaused(autoPaused);
            return result;
        } finally {
            store.writeLock().unlock();
        }
    }

    private boolean isBatchSettled(RolloutCampaign campaign, int batchIndex) {
        for (String deviceId : campaign.getBatches().get(batchIndex).getDeviceIds()) {
            UpgradeTask t = store.tasks().get(FleetStore.taskKey(campaign.getCampaignId(), deviceId));
            if (t == null || !t.isTerminal()) {
                return false;
            }
        }
        return true;
    }

    private long settledCount(RolloutCampaign campaign) {
        long n = 0;
        for (CampaignBatch b : campaign.getBatches()) {
            for (String deviceId : b.getDeviceIds()) {
                UpgradeTask t = store.tasks().get(FleetStore.taskKey(campaign.getCampaignId(), deviceId));
                if (t != null && t.isTerminal()) {
                    n++;
                }
            }
        }
        return n;
    }

    private CampaignStats buildStats(RolloutCampaign campaign) {
        CampaignStats stats = new CampaignStats();
        int settled = 0;
        int failedLike = 0;
        int attempts = 0;
        for (CampaignBatch b : campaign.getBatches()) {
            for (String deviceId : b.getDeviceIds()) {
                UpgradeTask t = store.tasks().get(FleetStore.taskKey(campaign.getCampaignId(), deviceId));
                if (t == null) {
                    continue;
                }
                stats.setTotal(stats.getTotal() + 1);
                attempts += t.getAttempts();
                switch (t.getStatus()) {
                    case DISPATCHED, ASSIGNED -> {
                        if (t.getStatus() == TaskStatus.DISPATCHED) {
                            stats.setDispatched(stats.getDispatched() + 1);
                        }
                    }
                    case SUCCESS -> {
                        stats.setSuccess(stats.getSuccess() + 1);
                        settled++;
                    }
                    case ROLLED_BACK -> {
                        stats.setRolledBack(stats.getRolledBack() + 1);
                        settled++;
                        failedLike++;
                    }
                    case FAILED -> {
                        stats.setFailed(stats.getFailed() + 1);
                        settled++;
                        failedLike++;
                    }
                }
            }
        }
        stats.setAttempts(attempts);
        stats.setFailureRate(settled == 0 ? 0.0 : (double) failedLike / settled);
        return stats;
    }

    private RolloutCampaign requireCampaign(String campaignId) {
        RolloutCampaign campaign = store.campaigns().get(campaignId);
        if (campaign == null) {
            throw new NotFoundException(RejectReason.CAMPAIGN_NOT_FOUND, "campaign not found: " + campaignId);
        }
        return campaign;
    }

    private CampaignView toView(RolloutCampaign campaign) {
        CampaignView view = new CampaignView();
        view.setCampaignId(campaign.getCampaignId());
        view.setFirmwareId(campaign.getFirmwareId());
        view.setTargetVersion(campaign.getTargetVersion());
        view.setPlatform(campaign.getPlatform());
        view.setFailureRateThreshold(campaign.getFailureRateThreshold());
        view.setStatus(campaign.getStatus());
        view.setActiveBatchIndex(campaign.getActiveBatchIndex());
        view.setPauseReason(campaign.getPauseReason());
        List<BatchView> batchViews = new ArrayList<>();
        for (CampaignBatch b : campaign.getBatches()) {
            BatchView bv = new BatchView();
            bv.setIndex(b.getIndex());
            bv.setStatus(b.getStatus());
            bv.setDeviceIds(new ArrayList<>(b.getDeviceIds()));
            batchViews.add(bv);
        }
        view.setBatches(batchViews);
        view.setStats(buildStats(campaign));
        return view;
    }

    /** 测试辅助：查看任务明细 */
    public TaskView taskView(String campaignId, String deviceId) {
        store.readLock().lock();
        try {
            UpgradeTask t = store.tasks().get(FleetStore.taskKey(campaignId, deviceId));
            if (t == null) {
                throw new NotFoundException(RejectReason.NO_ACTIVE_TASK,
                        "task not found: " + campaignId + ":" + deviceId);
            }
            TaskView v = new TaskView();
            v.setCampaignId(t.getCampaignId());
            v.setBatchIndex(t.getBatchIndex());
            v.setDeviceId(t.getDeviceId());
            v.setTargetVersion(t.getTargetVersion());
            v.setStatus(t.getStatus());
            v.setAttempts(t.getAttempts());
            v.setLastReportSeq(t.getLastReportSeq());
            v.setReportedVersion(t.getReportedVersion());
            v.setRollbackToVersion(t.getRollbackToVersion());
            v.setFailureReason(t.getFailureReason());
            return v;
        } finally {
            store.readLock().unlock();
        }
    }
}
