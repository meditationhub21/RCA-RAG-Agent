package com.example.rca.model;

import java.util.List;

/** Internal reasoner result. Evidence is referenced by IDs from RcaContext, never invented. */
public record RcaAnalysis(String description,String exceptionType,String suspectedExpression,String variable,
                          String reasoning,List<String> evidenceIds,String fixRecommendation,double confidence,
                          List<String> missingInformation,List<String> nextInvestigation,
                          String likelyIntroducingCommit,String unifiedDiff) {
    public RcaAnalysis {
        evidenceIds=List.copyOf(evidenceIds); missingInformation=List.copyOf(missingInformation);
        nextInvestigation=List.copyOf(nextInvestigation);
    }
}
