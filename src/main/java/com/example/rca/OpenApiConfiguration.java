package com.example.rca;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "RCA RAG Agent API",
        version = "1.0.0",
        description = "Repository synchronization, evidence-grounded root cause analysis, graph visualization, telemetry, and reviewed remediation workflows."))
public class OpenApiConfiguration {}