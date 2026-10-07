package com.example.rca.service;

import com.example.rca.model.ApiModels.RemediationRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class RemediationWorkflowServiceTest {
    @Test void remediationIsDisabledByDefault() {
        var workflow = new RemediationWorkflowService(mock(RepositorySyncService.class), false, false);
        assertThrows(IllegalStateException.class,
                () -> workflow.applyAndVerify(new RemediationRequest("petclinic", "diff", "fix", false)));
    }

    @Test void publishingRequiresItsSeparateOptIn() {
        var workflow = new RemediationWorkflowService(mock(RepositorySyncService.class), true, false);
        assertThrows(IllegalStateException.class,
                () -> workflow.applyAndVerify(new RemediationRequest("petclinic", "diff", "fix", true)));
    }
}
