package com.github.h3yow.otafleetrolloutjava.service;

import com.github.h3yow.otafleetrolloutjava.dto.RegisterFirmwareRequest;
import com.github.h3yow.otafleetrolloutjava.exception.NotFoundException;
import com.github.h3yow.otafleetrolloutjava.exception.RejectedException;
import com.github.h3yow.otafleetrolloutjava.model.Firmware;
import com.github.h3yow.otafleetrolloutjava.model.RejectReason;
import com.github.h3yow.otafleetrolloutjava.store.FleetStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

@Service
public class FirmwareService {

    private static final Logger log = LoggerFactory.getLogger(FirmwareService.class);

    private final FleetStore store;

    public FirmwareService(FleetStore store) {
        this.store = store;
    }

    public Firmware register(RegisterFirmwareRequest req) {
        if (req == null) {
            throw new RejectedException(RejectReason.INVALID_REQUEST, "request body is required");
        }
        if (DeviceService.isBlank(req.getFirmwareId())) {
            throw new RejectedException(RejectReason.INVALID_REQUEST, "firmwareId is required");
        }
        if (DeviceService.isBlank(req.getPlatform())) {
            throw new RejectedException(RejectReason.MISSING_PLATFORM,
                    "platform is missing for firmware " + req.getFirmwareId());
        }
        if (DeviceService.isBlank(req.getVersion())) {
            throw new RejectedException(RejectReason.MISSING_VERSION,
                    "version is missing for firmware " + req.getFirmwareId());
        }
        store.writeLock().lock();
        try {
            Firmware existing = store.firmwares().get(req.getFirmwareId());
            if (existing != null) {
                log.info("firmware register idempotent hit: firmwareId={}", req.getFirmwareId());
                return existing;
            }
            Firmware fw = new Firmware();
            fw.setFirmwareId(req.getFirmwareId().trim());
            fw.setPlatform(req.getPlatform().trim());
            fw.setVersion(req.getVersion().trim());
            fw.setMinBaseVersion(req.getMinBaseVersion() == null ? "0" : req.getMinBaseVersion().trim());
            fw.setCompatiblePlatforms(req.getCompatiblePlatforms() == null
                    ? new ArrayList<>() : new ArrayList<>(req.getCompatiblePlatforms()));
            fw.setCreatedAt(System.currentTimeMillis());
            store.firmwares().put(fw.getFirmwareId(), fw);
            store.persist();
            log.info("firmware registered: firmwareId={}, platform={}, version={}, minBase={}",
                    fw.getFirmwareId(), fw.getPlatform(), fw.getVersion(), fw.getMinBaseVersion());
            return fw;
        } finally {
            store.writeLock().unlock();
        }
    }

    public Firmware get(String firmwareId) {
        store.readLock().lock();
        try {
            Firmware fw = store.firmwares().get(firmwareId);
            if (fw == null) {
                throw new NotFoundException(RejectReason.FIRMWARE_NOT_FOUND, "firmware not found: " + firmwareId);
            }
            return fw;
        } finally {
            store.readLock().unlock();
        }
    }
}
