package com.github.h3yow.otafleetrolloutjava.repository;

import com.github.h3yow.otafleetrolloutjava.model.UpgradeRecord;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 升级记录本地持久化（内存模拟），key = campaignId:deviceId */
@Repository
public class UpgradeRecordRepository {
    private final ConcurrentHashMap<String, UpgradeRecord> store = new ConcurrentHashMap<>();

    private static String key(String campaignId, String deviceId) {
        return campaignId + ":" + deviceId;
    }

    public UpgradeRecord save(UpgradeRecord record) {
        store.put(key(record.getCampaignId(), record.getDeviceId()), record);
        return record;
    }

    public Optional<UpgradeRecord> find(String campaignId, String deviceId) {
        return Optional.ofNullable(store.get(key(campaignId, deviceId)));
    }

    public List<UpgradeRecord> findByCampaign(String campaignId) {
        return store.values().stream()
                .filter(r -> r.getCampaignId().equals(campaignId))
                .toList();
    }

    public void clear() {
        store.clear();
    }
}
