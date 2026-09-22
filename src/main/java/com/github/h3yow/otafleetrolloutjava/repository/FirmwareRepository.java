package com.github.h3yow.otafleetrolloutjava.repository;

import com.github.h3yow.otafleetrolloutjava.model.Firmware;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 固件本地持久化（内存模拟） */
@Repository
public class FirmwareRepository {
    private final ConcurrentHashMap<String, Firmware> store = new ConcurrentHashMap<>();

    public Firmware save(Firmware firmware) {
        store.put(firmware.getVersion(), firmware);
        return firmware;
    }

    public Optional<Firmware> findByVersion(String version) {
        return Optional.ofNullable(store.get(version));
    }

    public List<Firmware> findAll() {
        return List.copyOf(store.values());
    }

    public void clear() {
        store.clear();
    }
}
