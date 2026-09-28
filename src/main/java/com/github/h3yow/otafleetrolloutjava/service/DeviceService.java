package com.github.h3yow.otafleetrolloutjava.service;

import com.github.h3yow.otafleetrolloutjava.dto.HeartbeatRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetrolloutjava.dto.ReportUpgradeRequest;
import com.github.h3yow.otafleetrolloutjava.exception.RejectReason;
import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import com.github.h3yow.otafleetrolloutjava.model.*;
import com.github.h3yow.otafleetrolloutjava.repository.CampaignRepository;
import com.github.h3yow.otafleetrolloutjava.repository.DeviceRepository;
import com.github.h3yow.otafleetrolloutjava.repository.UpgradeRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 设备注册、心跳与升级结果上报 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceRepository deviceRepository;
    private final CampaignRepository campaignRepository;
    private final UpgradeRecordRepository recordRepository;
    private final CampaignService campaignService;

    public DeviceService(DeviceRepository deviceRepository,
                         CampaignRepository campaignRepository,
                         UpgradeRecordRepository recordRepository,
                         CampaignService campaignService) {
        this.deviceRepository = deviceRepository;
        this.campaignRepository = campaignRepository;
        this.recordRepository = recordRepository;
        this.campaignService = campaignService;
    }

    public Device register(RegisterDeviceRequest request) {
        if (isBlank(request.deviceId()) || isBlank(request.platform()) || isBlank(request.initialVersion())) {
            throw new RejectedException(RejectReason.MISSING_VERSION_OR_PLATFORM,
                    "deviceId/platform/initialVersion must all be present");
        }
        Device device = new Device(request.deviceId(), request.platform(), request.initialVersion());
        deviceRepository.save(device);
        log.info("[Device] registered id={} platform={} version={}",
                device.getDeviceId(), device.getPlatform(), device.getCurrentVersion());
        return device;
    }

    public Device heartbeat(HeartbeatRequest request) {
        if (isBlank(request.currentVersion()) || isBlank(request.platform())) {
            throw new RejectedException(RejectReason.MISSING_VERSION_OR_PLATFORM,
                    "heartbeat missing currentVersion or platform");
        }
        Device device = deviceRepository.findById(request.deviceId())
                .orElseThrow(() -> new RejectedException(RejectReason.DEVICE_NOT_FOUND,
                        "device not found: " + request.deviceId()));
        synchronized (device) {
            if (!device.getPlatform().equals(request.platform())) {
                throw new RejectedException(RejectReason.PLATFORM_MISMATCH,
                        "heartbeat platform " + request.platform()
                                + " != registered platform " + device.getPlatform());
            }
            device.setLastHeartbeatAt(Instant.now());
            device.setCurrentVersion(request.currentVersion());
            log.info("[Device] heartbeat id={} version={}", device.getDeviceId(), request.currentVersion());
            return device;
        }
    }

    /**
     * 处理升级结果上报。
     * 幂等与顺序保证：仅接受 sequence 严格大于已应用序号的首次结论；
     * 重复（同序号）与乱序/迟到（更小序号）上报被拒绝为 STALE_REPORT，不计数、不改状态。
     */
    public String reportUpgradeResult(ReportUpgradeRequest request) {
        if (isBlank(request.reportedVersion()) || isBlank(request.platform())) {
            throw new RejectedException(RejectReason.MISSING_VERSION_OR_PLATFORM,
                    "report missing reportedVersion or platform");
        }
        Device device = deviceRepository.findById(request.deviceId())
                .orElseThrow(() -> new RejectedException(RejectReason.DEVICE_NOT_FOUND,
                        "device not found: " + request.deviceId()));
        if (!device.getPlatform().equals(request.platform())) {
            throw new RejectedException(RejectReason.PLATFORM_MISMATCH,
                    "report platform " + request.platform()
                            + " != registered platform " + device.getPlatform());
        }
        Campaign campaign = campaignRepository.findById(request.campaignId())
                .orElseThrow(() -> new RejectedException(RejectReason.CAMPAIGN_NOT_FOUND,
                        "campaign not found: " + request.campaignId()));
        UpgradeRecord record = recordRepository.find(request.campaignId(), request.deviceId())
                .orElseThrow(() -> new RejectedException(RejectReason.ILLEGAL_DEVICE_STATE,
                        "device " + request.deviceId() + " not dispatched in campaign "
                                + request.campaignId()));

        log.info("[Report] device={} campaign={} seq={} success={} reportedVersion={} lastAppliedSeq={}",
                request.deviceId(), request.campaignId(), request.sequence(), request.success(),
                request.reportedVersion(), record.getLastAppliedSequence());

        synchronized (record) {
            if (request.sequence() <= record.getLastAppliedSequence()) {
                throw new RejectedException(RejectReason.STALE_REPORT,
                        "stale/duplicate report seq=" + request.sequence()
                                + " lastApplied=" + record.getLastAppliedSequence());
            }
            if (record.getStatus() != UpgradeStatus.DISPATCHED) {
                // 记录已进入终态（如更高序号上报已定论），新上报不得造成状态倒退
                throw new RejectedException(RejectReason.STALE_REPORT,
                        "record already terminal: " + record.getStatus());
            }
            record.setLastAppliedSequence(request.sequence());
            record.setUpdatedAt(Instant.now());
            synchronized (device) {
                if (request.success()) {
                    boolean versionMatches = record.getToVersion().equals(request.reportedVersion());
                    if (campaign.isControlled() && !versionMatches) {
                        // 受控发布严格验收：报告成功但实际版本对不上 → 明确失败并按回滚规则恢复
                        log.info("[Report] strict mismatch device={} reported={} target={} -> FAILED+auto-rollback",
                                request.deviceId(), request.reportedVersion(), record.getToVersion());
                        campaignService.failAndAutoRollback(record, device,
                                FailureReason.REPORTED_VERSION_MISMATCH);
                    } else {
                        record.setFailureReason(null);
                        record.setStatus(UpgradeStatus.SUCCEEDED);
                        device.setCurrentVersion(record.getToVersion());
                        device.setLastConfirmedVersion(record.getToVersion());
                        device.setStatus(DeviceStatus.IDLE);
                        device.setActiveCampaignId(null);
                    }
                } else {
                    record.setStatus(UpgradeStatus.FAILED);
                    record.setFailureReason(FailureReason.DEVICE_REPORTED_FAILURE);
                    device.setStatus(DeviceStatus.FAILED);
                    // 保留 activeCampaignId，供回滚定位
                }
            }
            log.info("[Report] applied device={} campaign={} newRecordStatus={}",
                    request.deviceId(), request.campaignId(), record.getStatus());
        }

        campaignService.autoPauseIfFailureRateExceeded(campaign.getCampaignId());
        campaignService.maybeComplete(campaign);
        return record.getStatus().name();
    }

    /** 机群当前版本分布 */
    public Map<String, Long> fleetVersionDistribution() {
        return deviceRepository.findAll().stream()
                .collect(Collectors.groupingBy(Device::getCurrentVersion,
                        TreeMap::new, Collectors.counting()));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
