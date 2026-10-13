package com.example.rca.service;

import com.example.rca.model.RcaAnalysis;
import com.example.rca.model.RcaContext;

/** Produces the model-backed incident analysis used by the RCA response. */
@FunctionalInterface
public interface RcaSynthesizer {
    RcaAnalysis synthesize(RcaContext context, RcaAnalysis evidenceAnalysis);

    /** Repair a Git-rejected proposal using its diagnostic; non-LLM synthesizers may decline. */
    default String repairPatch(RcaContext context, RcaAnalysis evidenceAnalysis, String rejectedPatch, String gitDiagnostic) {
        return "";
    }
}
