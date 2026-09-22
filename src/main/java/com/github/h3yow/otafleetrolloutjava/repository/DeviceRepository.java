package com.github.h3yow.otafleetrolloutjava.repository;

import com.github.h3yow.otafleetrolloutjava.model.Device;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 设备本地持久化（内存模拟） */
@Repository
public class DeviceRepository {
    private final ConcurrentHashMap<String, Device> store = new ConcurrentHashMap<>();

    public Device save(Device device) {
        store.put(device.getDeviceId(), device);
        return device;
    }

    public Optional<Device> findById(String deviceId) {
        return Optional.ofNullable(store.get(deviceId));
    }

    public List<Device> findAll() {
        return List.copyOf(store.values());
    }

    public void clear() {
        store.clear();
    }
}
