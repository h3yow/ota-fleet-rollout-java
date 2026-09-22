package com.github.h3yow.otafleetrolloutjava.web;

import com.github.h3yow.otafleetrolloutjava.dto.ApiResponse;
import com.github.h3yow.otafleetrolloutjava.dto.CampaignView;
import com.github.h3yow.otafleetrolloutjava.dto.CreateCampaignRequest;
import com.github.h3yow.otafleetrolloutjava.dto.HeartbeatRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterFirmwareRequest;
import com.github.h3yow.otafleetrolloutjava.dto.ReportResult;
import com.github.h3yow.otafleetrolloutjava.dto.UpgradeReportRequest;
import com.github.h3yow.otafleetrolloutjava.dto.VersionDistribution;
import com.github.h3yow.otafleetrolloutjava.model.Device;
import com.github.h3yow.otafleetrolloutjava.model.Firmware;
import com.github.h3yow.otafleetrolloutjava.service.CampaignService;
import com.github.h3yow.otafleetrolloutjava.service.DeviceService;
import com.github.h3yow.otafleetrolloutjava.service.FirmwareService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ota")
public class OtaController {

    private final DeviceService deviceService;
    private final FirmwareService firmwareService;
    private final CampaignService campaignService;

    public OtaController(DeviceService deviceService, FirmwareService firmwareService,
                         CampaignService campaignService) {
        this.deviceService = deviceService;
        this.firmwareService = firmwareService;
        this.campaignService = campaignService;
    }

    @PostMapping("/devices")
    public ApiResponse<Device> registerDevice(@RequestBody RegisterDeviceRequest req) {
        return ApiResponse.ok(deviceService.register(req));
    }

    @PostMapping("/devices/heartbeat")
    public ApiResponse<Device> heartbeat(@RequestBody HeartbeatRequest req) {
        return ApiResponse.ok(deviceService.heartbeat(req));
    }

    @GetMapping("/devices/{deviceId}")
    public ApiResponse<Device> getDevice(@PathVariable String deviceId) {
        return ApiResponse.ok(deviceService.get(deviceId));
    }

    @GetMapping("/devices/versions")
    public ApiResponse<VersionDistribution> versions(@RequestParam(required = false) String platform) {
        return ApiResponse.ok(deviceService.distribution(platform));
    }

    @PostMapping("/firmwares")
    public ApiResponse<Firmware> registerFirmware(@RequestBody RegisterFirmwareRequest req) {
        return ApiResponse.ok(firmwareService.register(req));
    }

    @PostMapping("/campaigns")
    public ApiResponse<CampaignView> createCampaign(@RequestBody CreateCampaignRequest req) {
        return ApiResponse.ok(campaignService.create(req));
    }

    @GetMapping("/campaigns/{campaignId}")
    public ApiResponse<CampaignView> getCampaign(@PathVariable String campaignId) {
        return ApiResponse.ok(campaignService.get(campaignId));
    }

    @PostMapping("/campaigns/{campaignId}/advance")
    public ApiResponse<CampaignView> advance(@PathVariable String campaignId) {
        return ApiResponse.ok(campaignService.activateNextBatch(campaignId));
    }

    @PostMapping("/campaigns/{campaignId}/pause")
    public ApiResponse<CampaignView> pause(@PathVariable String campaignId) {
        return ApiResponse.ok(campaignService.pause(campaignId));
    }

    @PostMapping("/campaigns/{campaignId}/resume")
    public ApiResponse<CampaignView> resume(@PathVariable String campaignId) {
        return ApiResponse.ok(campaignService.resume(campaignId));
    }

    @PostMapping("/campaigns/{campaignId}/terminate")
    public ApiResponse<CampaignView> terminate(@PathVariable String campaignId) {
        return ApiResponse.ok(campaignService.terminate(campaignId));
    }

    @PostMapping("/reports")
    public ApiResponse<ReportResult> report(@RequestBody UpgradeReportRequest req) {
        return ApiResponse.ok(campaignService.report(req));
    }
}
