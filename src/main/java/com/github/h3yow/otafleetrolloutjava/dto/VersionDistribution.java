package com.github.h3yow.otafleetrolloutjava.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/** 机群版本分布：版本 -> 设备数 */
public class VersionDistribution {
    private String platform;
    private int totalDevices;
    private Map<String, Integer> byVersion = new LinkedHashMap<>();

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public int getTotalDevices() { return totalDevices; }
    public void setTotalDevices(int totalDevices) { this.totalDevices = totalDevices; }
    public Map<String, Integer> getByVersion() { return byVersion; }
    public void setByVersion(Map<String, Integer> byVersion) { this.byVersion = byVersion; }
}
