package com.github.h3yow.otafleetrolloutjava.service;

import com.github.h3yow.otafleetrolloutjava.dto.CreateCampaignRequest;
import com.github.h3yow.otafleetrolloutjava.exception.RejectReason;
import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import com.github.h3yow.otafleetrolloutjava.model.*;
import com.github.h3yow.otafleetrolloutjava.repository.CampaignRepository;
import com.github.h3yow.otafleetrolloutjava.repository.DeviceRepository;
import com.github.h3yow.otafleetrolloutjava.repository.FirmwareRepository;
import com.github.h3yow.otafleetrolloutjava.repository.UpgradeRecordRepository;
import com.github.h3yow.otafleetrolloutjava.util.Versions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** 分批灰度升级活动编排：推进、暂停、恢复、终止、回滚 */
@Service
public class CampaignService {

    private static final Logger log = LoggerFactory.getLogger(CampaignService.class);

    private final CampaignRepository campaignRepository;
    private final DeviceRepository deviceRepository;
    private final FirmwareRepository firmwareRepository;
    private final UpgradeRecordRepository recordRepository;

    public CampaignService(CampaignRepository campaignRepository,
                           DeviceRepository deviceRepository,
                           FirmwareRepository firmwareRepository,
                           UpgradeRecordRepository recordRepository) {
        this.campaignRepository = campaignRepository;
        this.deviceRepository = deviceRepository;
        this.firmwareRepository = firmwareRepository;
        this.recordRepository = recordRepository;
    }

    public Campaign createCampaign(CreateCampaignRequest request) {
        Firmware firmware = firmwareRepository.findByVersion(request.targetFirmwareVersion())
                .orElseThrow(() -> new RejectedException(RejectReason.FIRMWARE_NOT_FOUND,
                        "target firmware not found: " + request.targetFirmwareVersion()));
        // 计划内重复检测：同一设备（可能跨批次）重复出现直接拒绝，首次出现保留
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (List<String> batch : request.batches()) {
            for (String deviceId : batch) {
                if (!seen.add(deviceId)) {
                    throw new RejectedException(RejectReason.DUPLICATE_DEVICE_IN_PLAN,
                            "device " + deviceId + " appears more than once in the campaign plan");
                }
            }
        }
        for (List<String> batch : request.batches()) {
            for (String deviceId : batch) {
                Device device = deviceRepository.findById(deviceId)
                        .orElseThrow(() -> new RejectedException(RejectReason.DEVICE_NOT_FOUND,
                                "device not found: " + deviceId));
                checkCompatible(device, firmware);
                String busy = device.getActiveCampaignId();
                if (busy != null) {
                    Campaign other = campaignRepository.findById(busy).orElse(null);
                    if (other != null && isInFlight(other.getStatus())) {
                        throw new RejectedException(RejectReason.DEVICE_BUSY_IN_OTHER_CAMPAIGN,
                                "device " + deviceId + " busy in campaign " + busy);
                    }
                }
            }
        }
        Campaign campaign = new Campaign(request.campaignId(), request.targetFirmwareVersion(),
                request.batches(), request.failureThresholdPercent(),
                request.controlled(), request.requireConfirmationAfterFirstBatch());
        campaignRepository.save(campaign);
        log.info("[Campaign] created id={} target={} batches={} threshold={}% controlled={} confirmAfterFirst={}",
                campaign.getCampaignId(), firmware.getVersion(),
                campaign.getBatches().size(), campaign.getFailureThresholdPercent(),
                campaign.isControlled(), campaign.isRequireConfirmationAfterFirstBatch());
        return campaign;
    }

    /**
     * 上线前彩排：只读地判定整份计划能否执行，按批次给出预计覆盖数与被阻塞设备明细。
     * 不创建活动、不占用设备、不写升级记录；目标固件不存在时直接拒绝。
     */
    public com.github.h3yow.otafleetrolloutjava.dto.rehearsal.RehearsalReport rehearse(
            CreateCampaignRequest request) {
        Firmware firmware = firmwareRepository.findByVersion(request.targetFirmwareVersion())
                .orElseThrow(() -> new RejectedException(RejectReason.FIRMWARE_NOT_FOUND,
                        "target firmware not found: " + request.targetFirmwareVersion()));

        java.util.Set<String> seenInPlan = new java.util.HashSet<>();
        List<com.github.h3yow.otafleetrolloutjava.dto.rehearsal.BatchPlan> batchPlans =
                new java.util.ArrayList<>();
        int totalPlanned = 0;
        int totalCovered = 0;
        int totalBlocked = 0;

        for (int b = 0; b < request.batches().size(); b++) {
            List<String> batch = request.batches().get(b);
            List<com.github.h3yow.otafleetrolloutjava.dto.rehearsal.BlockedDevice> blocked =
                    new java.util.ArrayList<>();
            int covered = 0;
            for (String deviceId : batch) {
                RejectReason reason = evaluateRehearsalDevice(deviceId, firmware, seenInPlan);
                if (reason == null) {
                    covered++;
                } else {
                    String message = rehearsalMessage(reason, deviceId);
                    blocked.add(new com.github.h3yow.otafleetrolloutjava.dto.rehearsal.BlockedDevice(
                            deviceId, reason.name(), message));
                    log.info("[Rehearsal] blocked batch={} device={} reason={}",
                            b, deviceId, reason);
                }
            }
            int planned = batch.size();
            totalPlanned += planned;
            totalCovered += covered;
            totalBlocked += blocked.size();
            batchPlans.add(new com.github.h3yow.otafleetrolloutjava.dto.rehearsal.BatchPlan(
                    b, planned, covered, List.copyOf(blocked)));
        }

        boolean feasible = totalBlocked == 0;
        log.info("[Rehearsal] id={} target={} feasible={} planned={} covered={} blocked={}",
                request.campaignId(), firmware.getVersion(), feasible,
                totalPlanned, totalCovered, totalBlocked);
        return new com.github.h3yow.otafleetrolloutjava.dto.rehearsal.RehearsalReport(
                request.campaignId(), firmware.getVersion(), feasible,
                totalPlanned, totalCovered, totalBlocked, List.copyOf(batchPlans));
    }

    /** 返回 null 表示该设备在彩排中可下发；否则返回可区分的阻塞原因 */
    private RejectReason evaluateRehearsalDevice(String deviceId, Firmware firmware,
                                                 java.util.Set<String> seenInPlan) {
        // 同一设备在计划里重复出现（含跨批次）：后续出现全部标记
        if (!seenInPlan.add(deviceId)) {
            return RejectReason.DUPLICATE_DEVICE_IN_PLAN;
        }
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            return RejectReason.DEVICE_NOT_FOUND;
        }
        if (!device.getPlatform().equals(firmware.getPlatform())) {
            return RejectReason.INCOMPATIBLE_FIRMWARE;
        }
        if (!Versions.satisfiesMinSource(device.getCurrentVersion(), firmware.getMinSourceVersion())) {
            return RejectReason.VERSION_CONSTRAINT_VIOLATED;
        }
        String busy = device.getActiveCampaignId();
        if (busy != null) {
            Campaign other = campaignRepository.findById(busy).orElse(null);
            if (other != null && isInFlight(other.getStatus())) {
                return RejectReason.DEVICE_BUSY_IN_OTHER_CAMPAIGN;
            }
        }
        return null;
    }

    private static String rehearsalMessage(RejectReason reason, String deviceId) {
        return switch (reason) {
            case DEVICE_NOT_FOUND -> "device not found: " + deviceId;
            case INCOMPATIBLE_FIRMWARE -> "device platform incompatible with target firmware: " + deviceId;
            case VERSION_CONSTRAINT_VIOLATED ->
                    "device current version below firmware minSourceVersion: " + deviceId;
            case DEVICE_BUSY_IN_OTHER_CAMPAIGN ->
                    "device occupied by another in-flight campaign: " + deviceId;
            case DUPLICATE_DEVICE_IN_PLAN ->
                    "device appears more than once in the plan: " + deviceId;
            default -> "blocked: " + deviceId;
        };
    }

    /** 推进到下一批次并下发；并发调用按活动串行化 */
    public Campaign advance(String campaignId) {
        Campaign campaign = getCampaign(campaignId);
        synchronized (campaign) {
            CampaignStatus status = campaign.getStatus();
            if (status == CampaignStatus.AWAITING_CONFIRMATION) {
                throw new RejectedException(RejectReason.ILLEGAL_CAMPAIGN_STATE,
                        "campaign is waiting for manual confirmation after first batch");
            }
            if (status == CampaignStatus.PAUSED) {
                throw new RejectedException(RejectReason.ILLEGAL_CAMPAIGN_STATE,
                        campaign.isAutoPaused()
                                ? "campaign stopped: failure threshold reached, resume required"
                                : "campaign manually paused, resume required");
            }
            if (status != CampaignStatus.CREATED && status != CampaignStatus.RUNNING) {
                throw new RejectedException(RejectReason.ILLEGAL_CAMPAIGN_STATE,
                        "cannot advance campaign in state " + status);
            }
            int next = campaign.getCurrentBatchIndex() + 1;
            if (next >= campaign.getBatches().size()) {
                throw new RejectedException(RejectReason.ILLEGAL_CAMPAIGN_STATE,
                        "no more batches to advance");
            }
            // 分批门禁：除首批外，上一批所有设备必须已有明确结论
            if (next > 0 && !isBatchSettled(campaign, next - 1)) {
                List<String> pending = pendingDevices(campaign, next - 1);
                throw new RejectedException(RejectReason.BATCH_NOT_SETTLED,
                        "previous batch " + (next - 1) + " not settled, devices without conclusion: " + pending);
            }
            campaign.setStatus(CampaignStatus.RUNNING);
            campaign.setCurrentBatchIndex(next);
            dispatchBatch(campaign, campaign.getBatches().get(next));
            log.info("[Campaign] advanced id={} to batch={} status={}",
                    campaignId, next, campaign.getStatus());
            return campaign;
        }
    }

    /** 首批结清后的人工确认：把活动从 AWAITING_CONFIRMATION 放行回 RUNNING（不直接下发） */
    public Campaign confirmFirstBatch(String campaignId) {
        Campaign campaign = getCampaign(campaignId);
        synchronized (campaign) {
            if (campaign.getStatus() != CampaignStatus.AWAITING_CONFIRMATION) {
                throw new RejectedException(RejectReason.ILLEGAL_CAMPAIGN_STATE,
                        "campaign not awaiting first-batch confirmation, current=" + campaign.getStatus());
            }
            campaign.setStatus(CampaignStatus.RUNNING);
            // 确认结论持久化在活动上：之后的手动暂停/恢复都不能抹掉它，
            // resume -> maybeComplete 不得再把活动退回 AWAITING_CONFIRMATION
            campaign.setFirstBatchConfirmed(true);
            log.info("[Campaign] first-batch confirmation received id={}, ready to advance", campaignId);
            return campaign;
        }
    }

    /** 某批次所有设备记录都已脱离 DISPATCHED（成功/失败/回滚均为明确结论） */
    private boolean isBatchSettled(Campaign campaign, int batchIndex) {
        for (String deviceId : campaign.getBatches().get(batchIndex)) {
            UpgradeRecord record = recordRepository.find(campaign.getCampaignId(), deviceId).orElse(null);
            if (record == null || record.getStatus() == UpgradeStatus.DISPATCHED) {
                return false;
            }
        }
        return true;
    }

    private List<String> pendingDevices(Campaign campaign, int batchIndex) {
        List<String> pending = new java.util.ArrayList<>();
        for (String deviceId : campaign.getBatches().get(batchIndex)) {
            UpgradeRecord record = recordRepository.find(campaign.getCampaignId(), deviceId).orElse(null);
            if (record == null || record.getStatus() == UpgradeStatus.DISPATCHED) {
                pending.add(deviceId);
            }
        }
        return pending;
    }

    public Campaign pause(String campaignId) {
        Campaign campaign = getCampaign(campaignId);
        synchronized (campaign) {
            if (campaign.getStatus() != CampaignStatus.RUNNING) {
                throw new RejectedException(RejectReason.ILLEGAL_CAMPAIGN_STATE,
                        "cannot pause campaign in state " + campaign.getStatus());
            }
            campaign.setStatus(CampaignStatus.PAUSED);
            campaign.setAutoPaused(false);
            log.info("[Campaign] manually paused id={}", campaignId);
            return campaign;
        }
    }

    public Campaign resume(String campaignId) {
        Campaign campaign = getCampaign(campaignId);
        synchronized (campaign) {
            if (campaign.getStatus() != CampaignStatus.PAUSED) {
                throw new RejectedException(RejectReason.ILLEGAL_CAMPAIGN_STATE,
                        "cannot resume campaign in state " + campaign.getStatus());
            }
            campaign.setStatus(CampaignStatus.RUNNING);
            campaign.setAutoPaused(false);
            log.info("[Campaign] resumed id={}", campaignId);
            // 恢复后重算结清结论：若批次实际已结清，需转入等待确认或完成
            maybeComplete(campaign);
            return campaign;
        }
    }

    public Campaign terminate(String campaignId) {
        Campaign campaign = getCampaign(campaignId);
        synchronized (campaign) {
            CampaignStatus status = campaign.getStatus();
            if (!isInFlight(status)) {
                throw new RejectedException(RejectReason.ILLEGAL_CAMPAIGN_STATE,
                        "cannot terminate campaign in state " + status);
            }
            campaign.setStatus(CampaignStatus.TERMINATED);
            // 释放仍处于下发中状态的设备
            for (UpgradeRecord record : recordRepository.findByCampaign(campaignId)) {
                if (record.getStatus() == UpgradeStatus.DISPATCHED) {
                    Device device = deviceRepository.findById(record.getDeviceId()).orElse(null);
                    if (device != null) {
                        synchronized (device) {
                            device.setStatus(DeviceStatus.IDLE);
                            device.setActiveCampaignId(null);
                        }
                    }
                }
            }
            log.info("[Campaign] terminated id={}", campaignId);
            return campaign;
        }
    }

    /** 回滚指定设备到最近一次确认可用版本 */
    public void rollbackDevice(String campaignId, String deviceId) {
        rollbackDeviceInternal(campaignId, deviceId);
        Campaign campaign = getCampaign(campaignId);
        maybeComplete(campaign);
    }

    /** 内部回滚：仅执行回滚本身，不触发活动结清评估（由调用方统一评估） */
    public void rollbackDeviceInternal(String campaignId, String deviceId) {
        Campaign campaign = getCampaign(campaignId);
        UpgradeRecord record = recordRepository.find(campaignId, deviceId)
                .orElseThrow(() -> new RejectedException(RejectReason.DEVICE_NOT_FOUND,
                        "no upgrade record for device " + deviceId + " in campaign " + campaignId));
        synchronized (record) {
            if (record.getStatus() != UpgradeStatus.FAILED) {
                throw new RejectedException(RejectReason.ILLEGAL_DEVICE_STATE,
                        "only FAILED record can roll back, current=" + record.getStatus());
            }
            Device device = deviceRepository.findById(deviceId)
                    .orElseThrow(() -> new RejectedException(RejectReason.DEVICE_NOT_FOUND,
                            "device not found: " + deviceId));
            synchronized (device) {
                String target = device.getLastConfirmedVersion();
                if (target == null) {
                    throw new RejectedException(RejectReason.NO_ROLLBACK_TARGET,
                            "no confirmed version to roll back to for device " + deviceId);
                }
                device.setCurrentVersion(target);
                device.setStatus(DeviceStatus.ROLLED_BACK);
                device.setActiveCampaignId(null);
                record.setStatus(UpgradeStatus.ROLLED_BACK);
                log.info("[Rollback] device={} campaign={} rolledBackTo={} reason={}",
                        deviceId, campaignId, target, record.getFailureReason());
            }
        }
    }

    /** 失败率超阈值时自动暂停（由上报路径触发） */
    public void autoPauseIfFailureRateExceeded(String campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null) {
            return;
        }
        synchronized (campaign) {
            if (campaign.getStatus() != CampaignStatus.RUNNING) {
                return;
            }
            List<UpgradeRecord> records = recordRepository.findByCampaign(campaignId);
            long finished = records.stream().filter(r -> r.getStatus() != UpgradeStatus.DISPATCHED).count();
            if (finished == 0) {
                return;
            }
            long failed = records.stream().filter(r ->
                    r.getStatus() == UpgradeStatus.FAILED || r.getStatus() == UpgradeStatus.ROLLED_BACK).count();
            double rate = failed * 100.0 / finished;
            log.info("[Campaign] failure-rate check id={} failed={}/{} rate={} threshold={}",
                    campaignId, failed, finished, String.format("%.2f", rate),
                    campaign.getFailureThresholdPercent());
            if (rate > campaign.getFailureThresholdPercent()) {
                campaign.setStatus(CampaignStatus.PAUSED);
                campaign.setAutoPaused(true);
                log.info("[Campaign] auto-paused id={} rate={} > threshold={}",
                        campaignId, String.format("%.2f", rate), campaign.getFailureThresholdPercent());
            }
        }
    }

    /** 全部批次已下发且所有记录进入终态时活动完成；首批结清后可按计划停在等待确认 */
    public void maybeComplete(Campaign campaign) {
        synchronized (campaign) {
            if (campaign.getStatus() != CampaignStatus.RUNNING) {
                return;
            }
            int current = campaign.getCurrentBatchIndex();
            if (current < 0) {
                return;
            }
            if (!isBatchSettled(campaign, current)) {
                return;
            }
            // 当前批次结清：还有后续批次时，先判断是否需要停在首批确认点
            if (current < campaign.getBatches().size() - 1) {
                if (current == 0 && campaign.isRequireConfirmationAfterFirstBatch()
                        && !campaign.isFirstBatchConfirmed()) {
                    campaign.setStatus(CampaignStatus.AWAITING_CONFIRMATION);
                    log.info("[Campaign] awaiting first-batch confirmation id={}", campaign.getCampaignId());
                }
                return;
            }
            campaign.setStatus(CampaignStatus.COMPLETED);
            log.info("[Campaign] completed id={}", campaign.getCampaignId());
        }
    }

    private void dispatchBatch(Campaign campaign, List<String> batch) {
        Firmware firmware = firmwareRepository.findByVersion(campaign.getTargetFirmwareVersion())
                .orElseThrow(() -> new RejectedException(RejectReason.FIRMWARE_NOT_FOUND,
                        "target firmware not found: " + campaign.getTargetFirmwareVersion()));
        for (String deviceId : batch) {
            Device device = deviceRepository.findById(deviceId)
                    .orElseThrow(() -> new RejectedException(RejectReason.DEVICE_NOT_FOUND,
                            "device not found: " + deviceId));
            synchronized (device) {
                checkCompatible(device, firmware);
                String busy = device.getActiveCampaignId();
                if (busy != null && !busy.equals(campaign.getCampaignId())) {
                    Campaign other = campaignRepository.findById(busy).orElse(null);
                    if (other != null && isInFlight(other.getStatus())) {
                        throw new RejectedException(RejectReason.DEVICE_BUSY_IN_OTHER_CAMPAIGN,
                                "device " + deviceId + " busy in campaign " + busy);
                    }
                }
                device.setStatus(DeviceStatus.UPGRADING);
                device.setActiveCampaignId(campaign.getCampaignId());
                recordRepository.save(new UpgradeRecord(deviceId, campaign.getCampaignId(),
                        device.getCurrentVersion(), firmware.getVersion()));
                log.info("[Dispatch] device={} campaign={} from={} to={}",
                        deviceId, campaign.getCampaignId(), device.getCurrentVersion(), firmware.getVersion());
            }
        }
    }

    private void checkCompatible(Device device, Firmware firmware) {
        if (!device.getPlatform().equals(firmware.getPlatform())) {
            throw new RejectedException(RejectReason.INCOMPATIBLE_FIRMWARE,
                    "firmware platform " + firmware.getPlatform()
                            + " incompatible with device platform " + device.getPlatform());
        }
        if (!Versions.satisfiesMinSource(device.getCurrentVersion(), firmware.getMinSourceVersion())) {
            throw new RejectedException(RejectReason.VERSION_CONSTRAINT_VIOLATED,
                    "device " + device.getDeviceId() + " version " + device.getCurrentVersion()
                            + " below minSourceVersion " + firmware.getMinSourceVersion());
        }
    }

    private Campaign getCampaign(String campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new RejectedException(RejectReason.CAMPAIGN_NOT_FOUND,
                        "campaign not found: " + campaignId));
    }

    private static boolean isInFlight(CampaignStatus status) {
        return status == CampaignStatus.CREATED || status == CampaignStatus.RUNNING
                || status == CampaignStatus.PAUSED
                || status == CampaignStatus.AWAITING_CONFIRMATION;
    }
}
