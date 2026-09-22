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
                request.batches(), request.failureThresholdPercent());
        campaignRepository.save(campaign);
        log.info("[Campaign] created id={} target={} batches={} threshold={}%",
                campaign.getCampaignId(), firmware.getVersion(),
                campaign.getBatches().size(), campaign.getFailureThresholdPercent());
        return campaign;
    }

    /** 推进到下一批次并下发；并发调用按活动串行化 */
    public Campaign advance(String campaignId) {
        Campaign campaign = getCampaign(campaignId);
        synchronized (campaign) {
            CampaignStatus status = campaign.getStatus();
            if (status != CampaignStatus.CREATED && status != CampaignStatus.RUNNING) {
                throw new RejectedException(RejectReason.ILLEGAL_CAMPAIGN_STATE,
                        "cannot advance campaign in state " + status);
            }
            int next = campaign.getCurrentBatchIndex() + 1;
            if (next >= campaign.getBatches().size()) {
                throw new RejectedException(RejectReason.ILLEGAL_CAMPAIGN_STATE,
                        "no more batches to advance");
            }
            campaign.setStatus(CampaignStatus.RUNNING);
            campaign.setCurrentBatchIndex(next);
            dispatchBatch(campaign, campaign.getBatches().get(next));
            log.info("[Campaign] advanced id={} to batch={} status={}",
                    campaignId, next, campaign.getStatus());
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

    /** 全部批次已下发且所有记录进入终态时，活动完成 */
    public void maybeComplete(Campaign campaign) {
        synchronized (campaign) {
            if (campaign.getStatus() != CampaignStatus.RUNNING) {
                return;
            }
            if (campaign.getCurrentBatchIndex() < campaign.getBatches().size() - 1) {
                return;
            }
            boolean allTerminal = recordRepository.findByCampaign(campaign.getCampaignId()).stream()
                    .allMatch(r -> r.getStatus() != UpgradeStatus.DISPATCHED);
            if (allTerminal) {
                campaign.setStatus(CampaignStatus.COMPLETED);
                log.info("[Campaign] completed id={}", campaign.getCampaignId());
            }
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
                || status == CampaignStatus.PAUSED;
    }
}
