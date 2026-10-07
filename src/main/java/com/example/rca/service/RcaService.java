package com.example.rca.service;

import com.example.rca.model.ApiModels.*;
import com.example.rca.model.RcaAnalysis;
import com.example.rca.model.RcaContext;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class RcaService {
    private static final Logger log = LoggerFactory.getLogger(RcaService.class);
    private final RepositorySyncService repositories;
    private final GraphStore graph;
    private final VectorStore vectors;
    private final RcaContextBuilder contextBuilder;
    private final DeterministicRcaReasoner reasoner;
    private final RcaSynthesizer synthesizer;
    private final ObjectMapper mapper;
    private final Map<String, Incident> incidents = new ConcurrentHashMap<>();

    public RcaService(RepositorySyncService repositories, GraphStore graph, VectorStore vectors,
                      RcaContextBuilder contextBuilder, DeterministicRcaReasoner reasoner, RcaSynthesizer synthesizer, ObjectMapper mapper) {
        this.repositories = repositories; this.graph = graph; this.vectors = vectors;
        this.contextBuilder = contextBuilder; this.reasoner = reasoner; this.synthesizer = synthesizer; this.mapper = mapper;
    }

    public RcaResponse investigate(String repo, String error, String trace) {
        if (repo == null || repo.isBlank() || error == null || error.isBlank())
            throw new IllegalArgumentException("repositoryName and errorMessage are required");
        long requestStarted = System.nanoTime();
        log.info("RCA investigation started: repository={} stackTraceProvided={}", repo, trace != null && !trace.isBlank());
        var snapshot = repositories.latest(repo).orElseThrow(() -> new IllegalArgumentException(
                "No persisted repository snapshot is available. Call POST /repositories/sync and verify the saved source directory is accessible."));
        Map<String, String> prior = incidents.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                e -> e.getValue().rootCause + " | " + e.getValue().resolution));
        long incidentHistoryStarted = System.nanoTime();
        graph.incidents(repo,List.of(),10).forEach(i -> prior.putIfAbsent("incident:"+i.caseId(),i.rootCause()+" | "+i.resolution()));
        log.info("RCA historical incident context loaded: repository={} elapsedMs={}", repo,
                (System.nanoTime() - incidentHistoryStarted) / 1_000_000);
        long contextStarted = System.nanoTime();
        RcaContext context = contextBuilder.build(repo, error, trace, snapshot, prior);
        log.info("RCA context built: repository={} commit={} location={} evidence={} quality={}", repo,
                context.currentCommit(), context.location(), context.evidence().size(), context.evidenceQuality());
        log.info("RCA context build elapsed: repository={} elapsedMs={}", repo, (System.nanoTime() - contextStarted) / 1_000_000);
        long synthesisStarted = System.nanoTime();
        RcaAnalysis analysis = synthesizer.synthesize(context, reasoner.analyze(context));
        log.info("RCA synthesis stage complete: repository={} elapsedMs={}", repo,
                (System.nanoTime() - synthesisStarted) / 1_000_000);
        List<Evidence> responseEvidence = context.evidence().stream()
                .filter(e -> analysis.evidenceIds().contains(e.id()))
                .map(e -> new Evidence(e.type(), e.details(), e.file(), e.line())).toList();
        var root = new RootCause(analysis.description(), context.location() == null ? null : context.location().file(),
                context.location() == null ? null : context.location().className(), context.location() == null ? null : context.location().method(),
                context.location() == null ? null : context.location().line(), analysis.exceptionType(),
                analysis.suspectedExpression(), analysis.variable());
        long incidentRetrievalStarted = System.nanoTime();
        var incidentMatches = vectors.search(error + " " + Objects.toString(trace, ""), 5, "incident");
        log.info("RCA response incident retrieval complete: repository={} matches={} elapsedMs={}", repo, incidentMatches.size(),
                (System.nanoTime() - incidentRetrievalStarted) / 1_000_000);
        var incidentIds = incidentMatches.stream().map(m -> m.id().substring("incident:".length())).toList();
        var persisted = graph.incidents(repo, incidentIds, 5);
        var matchScores = incidentMatches.stream().collect(Collectors.toMap(m -> m.id().substring("incident:".length()), VectorStore.Match::score, Math::max));
        var similar = new ArrayList<SimilarIncident>();
        for (var stored : persisted) similar.add(new SimilarIncident(stored.caseId(),stored.rootCause(),stored.resolution(),matchScores.getOrDefault(stored.caseId(),0.25)));
        if (similar.size() < 5) {
            var recentIds=similar.stream().map(SimilarIncident::caseId).collect(Collectors.toSet());
            graph.incidents(repo,List.of(),5).stream().filter(i->!recentIds.contains(i.caseId())).limit(5-similar.size())
                    .forEach(i->similar.add(new SimilarIncident(i.caseId(),i.rootCause(),i.resolution(),0.25)));
        }
        String caseId = UUID.randomUUID().toString();
        String summary = error + " " + Objects.toString(trace, "");
        Incident incident = new Incident(caseId, repo, analysis.description(), analysis.fixRecommendation(), summary);
        incidents.put("incident:" + caseId, incident);
        long incidentVectorStarted = System.nanoTime();
        vectors.upsert("incident:" + caseId, summary + " " + incident.rootCause + " " + incident.resolution);
        log.info("RCA incident embedding stored: repository={} elapsedMs={}", repo,
                (System.nanoTime() - incidentVectorStarted) / 1_000_000);
        long persistStarted = System.nanoTime();
        try {
            String serializedContext = mapper.writeValueAsString(context);
            graph.storeIncident(repo, caseId, error, root.file(), root.className(), root.method(), analysis.description(),
                    serializedContext, analysis.fixRecommendation(), analysis.confidence(), analysis.unifiedDiff());
        } catch (Exception e) { throw new IllegalStateException("Could not persist RCA context", e); }
        log.info("RCA case graph persistence complete: repository={} elapsedMs={}", repo,
                (System.nanoTime() - persistStarted) / 1_000_000);
        String limitation = "The LLM synthesized this RCA from source, graph, declared build dependencies, Git evidence, and any fresh runtime metrics. Live CPU/memory/pool/broker metrics are included only when a snapshot has been posted to /repositories/telemetry; a build file does not provide runtime measurements. Confidence is capped by evidence quality. Semantic retrieval uses PostgreSQL/pgvector.";
        log.info("RCA investigation completed: repository={} confidence={} evidenceItems={} caseId={} totalMs={}", repo,
                analysis.confidence(), responseEvidence.size(), caseId, (System.nanoTime() - requestStarted) / 1_000_000);
        return new RcaResponse(repo, caseId, root, responseEvidence, analysis.fixRecommendation(), analysis.confidence(), similar,
                limitation, analysis.reasoning(), analysis.missingInformation(), analysis.nextInvestigation(),
                analysis.likelyIntroducingCommit(), analysis.unifiedDiff());
    }

    private record Incident(String id, String repository, String rootCause, String resolution, String summary) {}
}
