package com.github.h3yow.otafleetrolloutjava.model;

/** 固件实体：携带平台与兼容约束 */
public class Firmware {
    private final String version;
    private final String platform;
    /** 可升级的最低源版本（含），源版本低于该值则不允许下发 */
    private final String minSourceVersion;
    private final String checksum;

    public Firmware(String version, String platform, String minSourceVersion, String checksum) {
        this.version = version;
        this.platform = platform;
        this.minSourceVersion = minSourceVersion;
        this.checksum = checksum;
    }

    public String getVersion() { return version; }
    public String getPlatform() { return platform; }
    public String getMinSourceVersion() { return minSourceVersion; }
    public String getChecksum() { return checksum; }
}
