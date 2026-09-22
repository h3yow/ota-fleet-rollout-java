package com.github.h3yow.otafleetrolloutjava.service;

import com.github.h3yow.otafleetrolloutjava.dto.HeartbeatRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetrolloutjava.dto.VersionDistribution;
import com.github.h3yow.otafleetrolloutjava.exception.NotFoundException;
import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import com.github.h3yow.otafleetrolloutjava.model.Device;
import com.github.h3yow.otafleetrolloutjava.model.RejectReason;
import com.github.h3yow.otafleetrolloutjava.store.FleetStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.TreeMap;

@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final FleetStore store;

    public DeviceService(FleetStore store) {
        this.store = store;
    }

    /** 注册设备：幂等（同 ID 重复注册直接返回已有记录）；字段缺失或平台不兼容时拒绝。 */
    public Device register(RegisterDeviceRequest req) {
        if (req == null) {
            throw new RejectedException(RejectReason.INVALID_REQUEST, "request body is required");
        }
        if (isBlank(req.getDeviceId())) {
            throw new RejectedException(RejectReason.INVALID_REQUEST, "deviceId is required");
        }
        if (isBlank(req.getPlatform())) {
            throw new RejectedException(RejectReason.MISSING_PLATFORM,
                    "platform is missing for device " + req.getDeviceId());
        }
        if (isBlank(req.getCurrentVersion())) {
            throw new RejectedException(RejectReason.MISSING_VERSION,
                    "currentVersion is missing for device " + req.getDeviceId());
        }
        store.writeLock().lock();
        try {
            Device existing = store.devices().get(req.getDeviceId());
            if (existing != null) {
                log.info("register idempotent hit: deviceId={}, platform={}, currentVersion={}",
                        req.getDeviceId(), existing.getPlatform(), existing.getCurrentVersion());
                return existing;
            }
            Device device = Device.register(req.getDeviceId(), req.getPlatform().trim(),
                    req.getCurrentVersion().trim(), System.currentTimeMillis());
            store.devices().put(device.getDeviceId(), device);
            store.persist();
            log.info("device registered: deviceId={}, platform={}, version={}",
                    device.getDeviceId(), device.getPlatform(), device.getCurrentVersion());
            return device;
        } finally {
            store.writeLock().unlock();
        }
    }

    /** 心跳：刷新时间戳；平台/版本缺失拒绝；平台与注册信息不一致拒绝。 */
    public Device heartbeat(HeartbeatRequest req) {
        if (req == null || isBlank(req.getDeviceId())) {
            throw new RejectedException(RejectReason.INVALID_REQUEST, "deviceId is required");
        }
        if (isBlank(req.getPlatform())) {
            throw new RejectedException(RejectReason.MISSING_PLATFORM,
                    "platform is missing in heartbeat from " + req.getDeviceId());
        }
        if (isBlank(req.getCurrentVersion())) {
            throw new RejectedException(RejectReason.MISSING_VERSION,
                    "currentVersion is missing in heartbeat from " + req.getDeviceId());
        }
        store.writeLock().lock();
        try {
            Device device = store.devices().get(req.getDeviceId());
            if (device == null) {
                throw new NotFoundException(RejectReason.DEVICE_NOT_FOUND,
                        "device not found: " + req.getDeviceId());
            }
            if (!device.getPlatform().equals(req.getPlatform().trim())) {
                log.warn("heartbeat rejected: deviceId={}, registeredPlatform={}, reportedPlatform={}",
                        req.getDeviceId(), device.getPlatform(), req.getPlatform());
                throw new RejectedException(RejectReason.PLATFORM_MISMATCH,
                        "platform mismatch: registered=" + device.getPlatform()
                                + ", reported=" + req.getPlatform());
            }
            device.setLastHeartbeatAt(System.currentTimeMillis());
            // 心跳上报版本与当前版本不一致时以心跳为准（设备可能已被线下刷新）
            if (!device.getCurrentVersion().equals(req.getCurrentVersion().trim())) {
                log.info("heartbeat version drift: deviceId={}, stored={}, reported={}",
                        device.getDeviceId(), device.getCurrentVersion(), req.getCurrentVersion());
                device.setCurrentVersion(req.getCurrentVersion().trim());
            }
            store.persist();
            return device;
        } finally {
            store.writeLock().unlock();
        }
    }

    public Device get(String deviceId) {
        store.readLock().lock();
        try {
            Device device = store.devices().get(deviceId);
            if (device == null) {
                throw new NotFoundException(RejectReason.DEVICE_NOT_FOUND, "device not found: " + deviceId);
            }
            return device;
        } finally {
            store.readLock().unlock();
        }
    }

    public Map<String, Device> all() {
        store.readLock().lock();
        try {
            return new TreeMap<>(store.devices());
        } finally {
            store.readLock().unlock();
        }
    }

    /** 机群版本分布，可按平台过滤（platform 为空则全量）。回滚后设备 currentVersion 已更新，故分布准确可查。 */
    public VersionDistribution distribution(String platform) {
        store.readLock().lock();
        try {
            VersionDistribution dist = new VersionDistribution();
            dist.setPlatform(platform);
            Map<String, Integer> counts = new TreeMap<>();
            int total = 0;
            for (Device d : store.devices().values()) {
                if (platform != null && !platform.isBlank() && !platform.equals(d.getPlatform())) {
                    continue;
                }
                total++;
                counts.merge(d.getCurrentVersion(), 1, Integer::sum);
            }
            dist.setTotalDevices(total);
            dist.setByVersion(counts);
            log.info("version distribution queried: platform={}, total={}, byVersion={}",
                    platform, total, counts);
            return dist;
        } finally {
            store.readLock().unlock();
        }
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
