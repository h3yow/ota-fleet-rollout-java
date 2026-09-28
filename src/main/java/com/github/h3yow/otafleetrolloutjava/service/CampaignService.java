package com.github.h3yow.otafleetrolloutjava.service;

import com.github.h3yow.otafleetrolloutjava.dto.CreateCampaignRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RehearsalBatchForecast;
import com.github.h3yow.otafleetrolloutjava.dto.RehearsalBlockedDevice;
import com.github.h3yow.otafleetrolloutjava.dto.RehearsalResult;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        Set<String> planned = new HashSet<>();
        for (List<String> batch : request.batches()) {
            for (String deviceId : batch) {
                if (!planned.add(deviceId)) {
                    throw new RejectedException(RejectReason.DUPLICATE_DEVICE_IN_PLAN,
                            "device " + deviceId + " appears more than once in the plan");
                }
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
                request.controlled(), request.waitAfterFirstBatch());
        campaignRepository.save(campaign);
        log.info("[Campaign] created id={} target={} batches={} threshold={}%",
                campaign.getCampaignId(), firmware.getVersion(),
                campaign.getBatches().size(), campaign.getFailureThresholdPercent());
        return campaign;
    }

    /** 上线前彩排：只读评估整条计划，不产生任何真实变更 */
    public RehearsalResult rehearse(CreateCampaignRequest request) {
        Firmware firmware = firmwareRepository.findByVersion(request.targetFirmwareVersion())
                .orElseThrow(() -> new RejectedException(RejectReason.FIRMWARE_NOT_FOUND,
                        "target firmware not found: " + request.targetFirmwareVersion()));

        Set<String> seen = new HashSet<>();
        List<RehearsalBatchForecast> forecasts = new ArrayList<>();
        boolean executable = true;

        for (int b = 0; b < request.batches().size(); b++) {
            List<String> batch = request.batches().get(b);
            List<RehearsalBlockedDevice> blocked = new ArrayList<>();
            Set<String> coverable = new HashSet<>();
            // 批内去重集合：同一设备在同批/跨批重复出现都算重复
            for (String deviceId : batch) {
                boolean firstSighting = seen.add(deviceId);
                RehearsalBlockedDevice blocker;
                if (!firstSighting) {
                    blocker = new RehearsalBlockedDevice(deviceId,
                            RejectReason.DUPLICATE_DEVICE_IN_PLAN.name(),
                            "device appears more than once in the plan");
                } else {
                    blocker = evaluateDeviceForRehearsal(deviceId, firmware);
                }
                if (blocker != null) {
                    blocked.add(blocker);
                } else {
                    // 首次出现且可下发才计入预计覆盖
                    coverable.add(deviceId);
                }
            }
            int covered = coverable.size();
            if (!blocked.isEmpty()) {
                executable = false;
            }
            forecasts.add(new RehearsalBatchForecast(b, batch.size(), covered, blocked));
        }

        log.info("[Rehearsal] target={} batches={} executable={} forecasts={}",
                firmware.getVersion(), request.batches().size(), executable,
                forecasts.stream().map(f -> "batch" + f.batchIndex() + ":ok=" + f.coveredCount()
                        + "/blocked=" + f.blockedDevices().size()).toList());
        return new RehearsalResult(executable, firmware.getVersion(), forecasts);
    }

    /** 评估单个设备是否会被阻塞；返回 null 表示可覆盖。纯只读。 */
    private RehearsalBlockedDevice evaluateDeviceForRehearsal(String deviceId, Firmware firmware) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            return new RehearsalBlockedDevice(deviceId, RejectReason.DEVICE_NOT_FOUND.name(),
                    "device not found: " + deviceId);
        }
        if (!device.getPlatform().equals(firmware.getPlatform())) {
            return new RehearsalBlockedDevice(deviceId, RejectReason.INCOMPATIBLE_FIRMWARE.name(),
                    "firmware platform " + firmware.getPlatform()
                            + " incompatible with device platform " + device.getPlatform());
        }
        if (!Versions.satisfiesMinSource(device.getCurrentVersion(), firmware.getMinSourceVersion())) {
            return new RehearsalBlockedDevice(deviceId, RejectReason.VERSION_CONSTRAINT_VIOLATED.name(),
                    "device version " + device.getCurrentVersion()
                            + " below minSourceVersion " + firmware.getMinSourceVersion());
        }
        String busy = device.getActiveCampaignId();
        if (busy != null) {
            Campaign other = campaignRepository.findById(busy).orElse(null);
            if (other != null && isInFlight(other.getStatus())) {
                return new RehearsalBlockedDevice(deviceId,
                        RejectReason.DEVICE_BUSY_IN_OTHER_CAMPAIGN.name(),
                        "device busy in campaign " + busy);
            }
        }
        return null;
    }

    /** 推进到下一批次并下发；并发调用按活动串行化 */
    public Campaign advance(String campaignId) {
        Campaign campaign = getCampaign(campaignId);
        synchronized (campaign) {
            CampaignStatus status = campaign.getStatus();
            if (status == CampaignStatus.AWAITING_CONFIRMATION) {
                throw new RejectedException(RejectReason.AWAITING_FIRST_BATCH_CONFIRMATION,
                        "campaign stopped after first batch, awaiting manual confirmation");
            }
            if (status == CampaignStatus.PAUSED) {
                if (campaign.isAutoPaused()) {
                    throw new RejectedException(RejectReason.STOP_CONDITION_TRIGGERED,
                            "failure stop condition triggered; resume required before advancing");
                }
                throw new RejectedException(RejectReason.ILLEGAL_CAMPAIGN_STATE,
                        "campaign manually paused; resume required before advancing");
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
            // 放行门禁：上一批所有设备都必须已有明确结论（终态）
            if (next > 0 && !isBatchSettled(campaign, next - 1)) {
                throw new RejectedException(RejectReason.PREVIOUS_BATCH_NOT_SETTLED,
                        "batch " + (next - 1) + " has devices without a final verdict yet");
            }
            campaign.setStatus(CampaignStatus.RUNNING);
            campaign.setCurrentBatchIndex(next);
            dispatchBatch(campaign, campaign.getBatches().get(next));
            log.info("[Campaign] advanced id={} to batch={} status={}",
                    campaignId, next, campaign.getStatus());
            return campaign;
        }
    }

    /** 首批结清后的人工确认；并发/重复确认只有一次生效，确认本身不下发 */
    public Campaign confirmFirstBatch(String campaignId) {
        Campaign campaign = getCampaign(campaignId);
        synchronized (campaign) {
            if (!campaign.isWaitAfterFirstBatch()) {
                throw new RejectedException(RejectReason.CONFIRMATION_NOT_REQUIRED,
                        "campaign is not configured to wait after the first batch");
            }
            if (campaign.isFirstBatchConfirmed()) {
                throw new RejectedException(RejectReason.CONFIRMATION_NOT_REQUIRED,
                        "first batch already confirmed");
            }
            if (campaign.getStatus() != CampaignStatus.AWAITING_CONFIRMATION) {
                throw new RejectedException(RejectReason.CONFIRMATION_NOT_REQUIRED,
                        "campaign not at the confirmation gate, current=" + campaign.getStatus());
            }
            campaign.setFirstBatchConfirmed(true);
            campaign.setStatus(CampaignStatus.RUNNING);
            log.info("[Campaign] first batch confirmed id={}, next advance will dispatch batch 1", campaignId);
            maybeComplete(campaign);
            return campaign;
        }
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
            // 若停下条件发生在首批结清时，resume 后仍需进入等待人工确认门
            maybeAwaitFirstBatchConfirmation(campaign);
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
                log.info("[Rollback] device={} campaign={} rolledBackTo={}",
                        deviceId, campaignId, target);
            }
        }
        maybeComplete(campaign);
    }

    /**
     * 受控发布下“成功但版本对不上”的结论处理：计为失败并立即按回滚规则恢复到
     * 设备此前确认可用的版本，避免停在看起来已升级、实际不可信的状态。
     * 调用方须已持有 record 锁与 device 锁。
     */
    public void failAndAutoRollback(UpgradeRecord record, Device device, FailureReason reason) {
        record.setStatus(UpgradeStatus.FAILED);
        record.setFailureReason(reason);
        String target = device.getLastConfirmedVersion();
        if (target == null) {
            throw new RejectedException(RejectReason.NO_ROLLBACK_TARGET,
                    "no confirmed version to roll back to for device " + device.getDeviceId());
        }
        device.setCurrentVersion(target);
        device.setStatus(DeviceStatus.ROLLED_BACK);
        device.setActiveCampaignId(null);
        record.setStatus(UpgradeStatus.ROLLED_BACK);
        log.info("[Strict] device={} campaign={} rejected reported upgrade (reason={}); auto rolled back to {}",
                device.getDeviceId(), record.getCampaignId(), reason, target);
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
            long finished = records.stream().filter(r -> r.getStatus().isTerminal()).count();
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

    /** 全部批次已下发且所有记录进入终态时，活动完成；否则若首批刚结清则停在等待确认 */
    public void maybeComplete(Campaign campaign) {
        synchronized (campaign) {
            if (campaign.getStatus() != CampaignStatus.RUNNING) {
                return;
            }
            if (campaign.getCurrentBatchIndex() < campaign.getBatches().size() - 1) {
                // 还有后续批次：若首批刚结清且配置了人工确认，则停在确认门
                maybeAwaitFirstBatchConfirmation(campaign);
                return;
            }
            boolean allTerminal = recordRepository.findByCampaign(campaign.getCampaignId()).stream()
                    .allMatch(r -> r.getStatus().isTerminal());
            if (allTerminal) {
                campaign.setStatus(CampaignStatus.COMPLETED);
                log.info("[Campaign] completed id={}", campaign.getCampaignId());
            }
        }
    }

    /** 首批（批次0）所有设备都已结清时，按配置停在等待人工确认状态 */
    private void maybeAwaitFirstBatchConfirmation(Campaign campaign) {
        if (!campaign.isWaitAfterFirstBatch() || campaign.isFirstBatchConfirmed()) {
            return;
        }
        if (campaign.getCurrentBatchIndex() != 0 || campaign.isAutoPaused()) {
            return;
        }
        if (isBatchSettled(campaign, 0)) {
            campaign.setStatus(CampaignStatus.AWAITING_CONFIRMATION);
            log.info("[Campaign] first batch settled, awaiting manual confirmation id={}",
                    campaign.getCampaignId());
        }
    }

    /** 指定批次的每台设备是否都已有明确结论（升级记录终态） */
    private boolean isBatchSettled(Campaign campaign, int batchIndex) {
        List<String> batch = campaign.getBatches().get(batchIndex);
        for (String deviceId : batch) {
            UpgradeRecord record = recordRepository.find(campaign.getCampaignId(), deviceId)
                    .orElse(null);
            // 尚未下发记录或仍在等待上报，都视为未结清
            if (record == null || !record.getStatus().isTerminal()) {
                return false;
            }
        }
        return true;
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
