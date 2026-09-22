package com.github.h3yow.otafleetrolloutjava.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.otafleetrolloutjava.model.Device;
import com.github.h3yow.otafleetrolloutjava.model.Firmware;
import com.github.h3yow.otafleetrolloutjava.model.RolloutCampaign;
import com.github.h3yow.otafleetrolloutjava.model.UpgradeTask;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 本地持久化存储：内存结构 + JSON 快照文件。
 * 所有业务写操作在服务层持有 writeLock，保证并发下状态一致；
 * 每次变更后调用 persist() 落盘快照，进程重启后可恢复（模拟本地持久化）。
 */
@Component
public class FleetStore {

    private static final Logger log = LoggerFactory.getLogger(FleetStore.class);

    private final Map<String, Device> devices = new LinkedHashMap<>();
    private final Map<String, Firmware> firmwares = new LinkedHashMap<>();
    private final Map<String, RolloutCampaign> campaigns = new LinkedHashMap<>();
    private final Map<String, UpgradeTask> tasks = new LinkedHashMap<>(); // key = campaignId:deviceId

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Value("${ota.store.file:target/ota-store.json}")
    private String storeFile;

    @PostConstruct
    public void init() {
        File f = file();
        if (!f.exists() || f.length() == 0) {
            log.info("OTA store file not found, starting empty: {}", f.getAbsolutePath());
            return;
        }
        writeLock().lock();
        try {
            Snapshot snapshot = mapper.readValue(f, Snapshot.class);
            devices.clear();
            firmwares.clear();
            campaigns.clear();
            tasks.clear();
            snapshot.devices.forEach(d -> devices.put(d.getDeviceId(), d));
            snapshot.firmwares.forEach(fw -> firmwares.put(fw.getFirmwareId(), fw));
            snapshot.campaigns.forEach(c -> campaigns.put(c.getCampaignId(), c));
            snapshot.tasks.forEach(t -> tasks.put(taskKey(t.getCampaignId(), t.getDeviceId()), t));
            log.info("OTA store loaded from {}: devices={}, firmwares={}, campaigns={}, tasks={}",
                    f.getAbsolutePath(), devices.size(), firmwares.size(), campaigns.size(), tasks.size());
        } catch (Exception e) {
            log.warn("Failed to load OTA store from {}: {}", f.getAbsolutePath(), e.getMessage());
        } finally {
            writeLock().unlock();
        }
    }

    /** 将当前内存状态原子写入快照文件（先写临时文件再 rename）。调用方应已持有写锁。 */
    public void persist() {
        File f = file();
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Snapshot snapshot = new Snapshot();
            snapshot.devices = new ArrayList<>(devices.values());
            snapshot.firmwares = new ArrayList<>(firmwares.values());
            snapshot.campaigns = new ArrayList<>(campaigns.values());
            snapshot.tasks = new ArrayList<>(tasks.values());
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, snapshot);
            if (!tmp.renameTo(f)) {
                // 某些平台 rename 失败时回退为直接覆盖写
                mapper.writerWithDefaultPrettyPrinter().writeValue(f, snapshot);
            }
        } catch (Exception e) {
            log.error("Persist OTA store failed: {}", e.getMessage(), e);
            throw new IllegalStateException("persist failed", e);
        } finally {
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    public Map<String, Device> devices() { return devices; }
    public Map<String, Firmware> firmwares() { return firmwares; }
    public Map<String, RolloutCampaign> campaigns() { return campaigns; }
    public Map<String, UpgradeTask> tasks() { return tasks; }

    public static String taskKey(String campaignId, String deviceId) {
        return campaignId + ":" + deviceId;
    }

    public ReentrantReadWriteLock.WriteLock writeLock() { return lock.writeLock(); }
    public ReentrantReadWriteLock.ReadLock readLock() { return lock.readLock(); }

    public File file() { return new File(storeFile); }
    public ObjectMapper mapper() { return mapper; }
    public String storeFile() { return storeFile; }

    /** JSON 快照结构 */
    public static class Snapshot {
        public List<Device> devices = new ArrayList<>();
        public List<Firmware> firmwares = new ArrayList<>();
        public List<RolloutCampaign> campaigns = new ArrayList<>();
        public List<UpgradeTask> tasks = new ArrayList<>();
    }
}
