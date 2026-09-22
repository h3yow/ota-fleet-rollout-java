package com.github.h3yow.otafleetrolloutjava.dto;

public record RegisterFirmwareRequest(String version, String platform,
                                      String minSourceVersion, String checksum) {
}
