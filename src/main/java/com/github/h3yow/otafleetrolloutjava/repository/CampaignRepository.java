package com.github.h3yow.otafleetrolloutjava.repository;

import com.github.h3yow.otafleetrolloutjava.model.Campaign;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 升级活动本地持久化（内存模拟） */
@Repository
public class CampaignRepository {
    private final ConcurrentHashMap<String, Campaign> store = new ConcurrentHashMap<>();

    public Campaign save(Campaign campaign) {
        store.put(campaign.getCampaignId(), campaign);
        return campaign;
    }

    public Optional<Campaign> findById(String campaignId) {
        return Optional.ofNullable(store.get(campaignId));
    }

    public List<Campaign> findAll() {
        return List.copyOf(store.values());
    }

    public void clear() {
        store.clear();
    }
}
