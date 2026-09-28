package com.github.h3yow.otafleetrolloutjava.dto.rehearsal;

import java.util.List;

/** 单批次彩排结果：预计覆盖设备数与被阻塞设备明细 */
public record BatchPlan(int batchIndex, int plannedDevices,
                        int coveredDevices, List<BlockedDevice> blockedDevices) {
}
