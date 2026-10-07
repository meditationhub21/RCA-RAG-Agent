package com.example.rca.controller;

import com.example.rca.model.ApiModels.RemediationRequest;
import com.example.rca.model.ApiModels.RemediationPublishRequest;
import com.example.rca.model.ApiModels.RemediationResponse;
import com.example.rca.service.RemediationWorkflowService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/repositories/remediation")
public class RemediationController {
    private final RemediationWorkflowService workflow;
    public RemediationController(RemediationWorkflowService workflow) { this.workflow = workflow; }
    @PostMapping
    public RemediationResponse applyAndVerify(@RequestBody RemediationRequest request) {
        return workflow.applyAndVerify(request);
    }
    @PostMapping("/publish")
    public RemediationResponse publish(@RequestBody RemediationPublishRequest request) {
        return workflow.publish(request);
    }
}
