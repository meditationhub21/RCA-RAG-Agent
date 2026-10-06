package com.example.rca.model;

import java.util.List;

public final class ApiModels {
    private ApiModels() {}
    public record SyncRequest(String repositoryName, String sourceDirectory) {}
    public record SyncResponse(String repository, String status, String commit, int filesScanned,
                               int filesChanged, int graphNodesUpdated, int embeddingsUpdated,
                               java.util.List<String> declaredDependencies) {
        public SyncResponse(String repository,String status,String commit,int filesScanned,int filesChanged,int graphNodesUpdated,int embeddingsUpdated) {
            this(repository,status,commit,filesScanned,filesChanged,graphNodesUpdated,embeddingsUpdated,java.util.List.of());
        }
        public SyncResponse {declaredDependencies=java.util.List.copyOf(declaredDependencies);}
    }
    public record RcaRequest(String repositoryName, String errorMessage, String stackTrace) {}
    public record TelemetryRequest(String repositoryName,String source,String observedAt,
                                   java.util.Map<String,Double> metrics,java.util.Map<String,String> units,
                                   java.util.Map<String,String> labels) {}
    public record TelemetryResponse(String repository,String status,int metricsAccepted,String observedAt) {}
    public record RootCause(String description, String file,
                            @com.fasterxml.jackson.annotation.JsonProperty("class") String className,
                            String method, Integer line, String exceptionType,
                            String suspectedExpression, String variable) {}
    public record Evidence(String type, String details, String file, Integer line) {}
    public record SimilarIncident(String caseId, String rootCause, String resolution, double similarity) {}
    public record RcaResponse(String repository, RootCause rootCause, List<Evidence> evidence,
                              String fixRecommendation, double confidence, List<SimilarIncident> similarIncidents,
                              String limitation, String reasoning, List<String> missingInformation,
                              List<String> nextInvestigation, String likelyIntroducingCommit) {}
    public record ApiError(String error, String message) {}
}
