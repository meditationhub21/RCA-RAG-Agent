package com.example.rca.model;

import java.util.List;

/** Bounded, serializable evidence packet supplied to the RCA reasoner. */
public record RcaContext(
        String repository,
        String repositoryDirectory,
        String currentCommit,
        List<CodeModels.DependencyInfo> declaredDependencies,
        List<RuntimeMetric> runtimeMetrics,
        String errorMessage,
        String exceptionType,
        String stackTrace,
        FailureLocation location,
        String suspectedExpression,
        String variableContext,
        String sourceAroundFailure,
        String methodSource,
        List<String> callers,
        List<String> callees,
        List<String> dependencies,
        List<String> exceptions,
        List<String> relatedTests,
        List<String> gitHistory,
        List<String> gitDiffs,
        String likelyIntroducingCommit,
        List<String> configuration,
        List<ContextEvidence> evidence,
        List<String> semanticMatches,
        List<String> similarIncidents,
        String evidenceQuality,
        List<String> missingInformation) {

    public RcaContext {
        declaredDependencies=List.copyOf(declaredDependencies);runtimeMetrics=List.copyOf(runtimeMetrics);
        callers=List.copyOf(callers); callees=List.copyOf(callees); dependencies=List.copyOf(dependencies);
        exceptions=List.copyOf(exceptions); relatedTests=List.copyOf(relatedTests); gitHistory=List.copyOf(gitHistory);
        gitDiffs=List.copyOf(gitDiffs); configuration=List.copyOf(configuration); evidence=List.copyOf(evidence);
        semanticMatches=List.copyOf(semanticMatches); similarIncidents=List.copyOf(similarIncidents);
        missingInformation=List.copyOf(missingInformation);
    }
    /** Backwards-compatible constructor for callers that do not yet supply dependency/metric inventories. */
    public RcaContext(String repository,String repositoryDirectory,String currentCommit,String errorMessage,String exceptionType,String stackTrace,
                      FailureLocation location,String suspectedExpression,String variableContext,String sourceAroundFailure,String methodSource,
                      List<String> callers,List<String> callees,List<String> dependencies,List<String> exceptions,List<String> relatedTests,
                      List<String> gitHistory,List<String> gitDiffs,String likelyIntroducingCommit,List<String> configuration,
                      List<ContextEvidence> evidence,List<String> semanticMatches,List<String> similarIncidents,String evidenceQuality,
                      List<String> missingInformation) {
        this(repository,repositoryDirectory,currentCommit,List.of(),List.of(),errorMessage,exceptionType,stackTrace,location,suspectedExpression,
                variableContext,sourceAroundFailure,methodSource,callers,callees,dependencies,exceptions,relatedTests,gitHistory,gitDiffs,
                likelyIntroducingCommit,configuration,evidence,semanticMatches,similarIncidents,evidenceQuality,missingInformation);
    }
    public record FailureLocation(String file,String className,String method,Integer line) {}
    public record ContextEvidence(String id,String type,String details,String file,Integer line) {}
    public record RuntimeMetric(String name,double value,String unit,String labels,String source,String observedAt) {
        public RuntimeMetric(String name,double value,String unit,String labels,String source){this(name,value,unit,labels,source,null);}
    }
}
