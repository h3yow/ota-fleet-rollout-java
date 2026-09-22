package com.github.h3yow.otafleetrolloutjava.controller;

import com.github.h3yow.otafleetrolloutjava.dto.HeartbeatRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetrolloutjava.dto.ReportUpgradeRequest;
import com.github.h3yow.otafleetrolloutjava.model.Device;
import com.github.h3yow.otafleetrolloutjava.service.DeviceService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping("/register")
    public Device register(@RequestBody RegisterDeviceRequest request) {
        return deviceService.register(request);
    }

    @PostMapping("/heartbeat")
    public Device heartbeat(@RequestBody HeartbeatRequest request) {
        return deviceService.heartbeat(request);
    }

    @PostMapping("/report")
    public Map<String, String> report(@RequestBody ReportUpgradeRequest request) {
        return Map.of("result", deviceService.reportUpgradeResult(request));
    }

    @GetMapping("/version-distribution")
    public Map<String, Long> versionDistribution() {
        return deviceService.fleetVersionDistribution();
    }
}
