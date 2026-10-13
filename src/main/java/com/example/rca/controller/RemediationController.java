package com.example.rca.controller;

import com.example.rca.model.ApiModels.RemediationRequest;
import com.example.rca.model.ApiModels.RemediationPublishRequest;
import com.example.rca.model.ApiModels.RemediationResponse;
import com.example.rca.service.RemediationWorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/repositories/remediation")
@Tag(name = "Remediation", description = "Human-triggered patch application, verification, and optional publication")
public class RemediationController {
    private final RemediationWorkflowService workflow;
    public RemediationController(RemediationWorkflowService workflow) { this.workflow = workflow; }
    @PostMapping
    @Operation(summary = "Apply and verify an RCA patch", description = "Retrieves the safe patch stored with an RCA case, creates a codex/rca-* branch, applies the patch, optionally formats it with the configured project formatter, runs the project's verification command, and commits verified changes. Requires rca.remediation.enabled=true.")
    public RemediationResponse applyAndVerify(@RequestBody RemediationRequest request) {
        return workflow.applyAndVerify(request);
    }
    @PostMapping("/publish")
    @Operation(summary = "Push a reviewed remediation branch and open a pull request", description = "Publishes only the requested current codex/rca-* branch after a clean-worktree and branch-name check. Requires rca.remediation.publishing-enabled=true and configured GitHub CLI authentication.")
    public RemediationResponse publish(@RequestBody RemediationPublishRequest request) {
        return workflow.publish(request);
    }
}