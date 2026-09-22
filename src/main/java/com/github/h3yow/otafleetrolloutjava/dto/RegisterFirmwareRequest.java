package com.github.h3yow.otafleetrolloutjava.dto;

import java.util.List;

public class RegisterFirmwareRequest {
    private String firmwareId;
    private String platform;
    private String version;
    private String minBaseVersion;
    private List<String> compatiblePlatforms;

    public String getFirmwareId() { return firmwareId; }
    public void setFirmwareId(String firmwareId) { this.firmwareId = firmwareId; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getMinBaseVersion() { return minBaseVersion; }
    public void setMinBaseVersion(String minBaseVersion) { this.minBaseVersion = minBaseVersion; }
    public List<String> getCompatiblePlatforms() { return compatiblePlatforms; }
    public void setCompatiblePlatforms(List<String> compatiblePlatforms) { this.compatiblePlatforms = compatiblePlatforms; }
}
