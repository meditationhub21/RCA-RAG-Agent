package com.example.rca.controller;

import com.example.rca.model.ApiModels.TelemetryRequest;
import com.example.rca.model.ApiModels.TelemetryResponse;
import com.example.rca.service.RuntimeTelemetryStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/repositories")
public class TelemetryController {
    private final RuntimeTelemetryStore store;
    public TelemetryController(RuntimeTelemetryStore store){this.store=store;}
    @PostMapping("/telemetry")
    public TelemetryResponse ingest(@RequestBody TelemetryRequest request){
        var snapshot=store.accept(request.repositoryName(),request.source(),request.observedAt(),request.metrics(),request.units(),request.labels());
        return new TelemetryResponse(snapshot.repository(),"ACCEPTED",snapshot.metrics().size(),snapshot.observedAt());
    }
}
