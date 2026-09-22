package com.github.h3yow.otafleetrolloutjava.controller;

import com.github.h3yow.otafleetrolloutjava.dto.CreateCampaignRequest;
import com.github.h3yow.otafleetrolloutjava.dto.RegisterFirmwareRequest;
import com.github.h3yow.otafleetrolloutjava.model.Campaign;
import com.github.h3yow.otafleetrolloutjava.model.Firmware;
import com.github.h3yow.otafleetrolloutjava.repository.FirmwareRepository;
import com.github.h3yow.otafleetrolloutjava.service.CampaignService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private final CampaignService campaignService;
    private final FirmwareRepository firmwareRepository;

    public CampaignController(CampaignService campaignService, FirmwareRepository firmwareRepository) {
        this.campaignService = campaignService;
        this.firmwareRepository = firmwareRepository;
    }

    @PostMapping("/firmware")
    public Firmware registerFirmware(@RequestBody RegisterFirmwareRequest request) {
        return firmwareRepository.save(new Firmware(request.version(), request.platform(),
                request.minSourceVersion(), request.checksum()));
    }

    @PostMapping
    public Campaign create(@RequestBody CreateCampaignRequest request) {
        return campaignService.createCampaign(request);
    }

    @PostMapping("/{id}/advance")
    public Campaign advance(@PathVariable String id) {
        return campaignService.advance(id);
    }

    @PostMapping("/{id}/pause")
    public Campaign pause(@PathVariable String id) {
        return campaignService.pause(id);
    }

    @PostMapping("/{id}/resume")
    public Campaign resume(@PathVariable String id) {
        return campaignService.resume(id);
    }

    @PostMapping("/{id}/terminate")
    public Campaign terminate(@PathVariable String id) {
        return campaignService.terminate(id);
    }

    @PostMapping("/{id}/devices/{deviceId}/rollback")
    public void rollback(@PathVariable String id, @PathVariable String deviceId) {
        campaignService.rollbackDevice(id, deviceId);
    }
}
