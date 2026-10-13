package com.example.rca.service;

import com.example.rca.model.ApiModels.*;
import com.example.rca.model.RcaAnalysis;
import com.example.rca.model.RcaContext;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
        RcaAnalysis synthesizedAnalysis = synthesizer.synthesize(context, reasoner.analyze(context));
        String applicablePatch = "";
        String candidatePatch = synthesizedAnalysis.unifiedDiff();
        for (int attempt = 1; attempt <= 2 && candidatePatch != null && !candidatePatch.isBlank(); attempt++) {
            PatchCheck patchCheck = validatePatch(snapshot.directory(), candidatePatch, repo);
            if (patchCheck.accepted()) {
                applicablePatch = candidatePatch;
                break;
            }
            log.warn("RCA patch rejected by Git: repository={} attempt={} diagnostic={}", repo, attempt, patchCheck.diagnostic());
            if (attempt < 2) {
                candidatePatch = synthesizer.repairPatch(context, synthesizedAnalysis, candidatePatch, patchCheck.diagnostic());
            }
        }
        RcaAnalysis analysis = Objects.equals(applicablePatch, synthesizedAnalysis.unifiedDiff())
                ? synthesizedAnalysis
                : new RcaAnalysis(synthesizedAnalysis.description(), synthesizedAnalysis.exceptionType(),
                        synthesizedAnalysis.suspectedExpression(), synthesizedAnalysis.variable(),
                        synthesizedAnalysis.reasoning(), synthesizedAnalysis.evidenceIds(),
                        synthesizedAnalysis.fixRecommendation(), synthesizedAnalysis.confidence(),
                        synthesizedAnalysis.missingInformation(), synthesizedAnalysis.nextInvestigation(),
                        synthesizedAnalysis.likelyIntroducingCommit(), applicablePatch);
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

    /** Only persist patches that Git itself can parse and apply to the synchronized repository. */
    private PatchCheck validatePatch(String repositoryDirectory, String patch, String repository) {
        if (patch == null || patch.isBlank()) return new PatchCheck(false, "No patch proposal was supplied.");
        Path patchFile = null;
        Path outputFile = null;
        try {
            patchFile = Files.createTempFile("rca-patch-check-", ".patch");
            outputFile = Files.createTempFile("rca-patch-check-", ".log");
            Files.writeString(patchFile, patch, StandardCharsets.UTF_8);
            Process process = new ProcessBuilder("git", "apply", "--recount", "--check", "--ignore-whitespace",
                    "--whitespace=nowarn", "--", patchFile.toString())
                    .directory(Path.of(repositoryDirectory).toAbsolutePath().normalize().toFile())
                    .redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("RCA patch Git validation timed out: repository={}", repository);
                return new PatchCheck(false, "git apply --check timed out after 30 seconds");
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                log.warn("RCA patch rejected by git apply --check: repository={} exitCode={} output={}",
                        repository, process.exitValue(), output);
                return new PatchCheck(false, output.isBlank() ? "git apply --check exited with " + process.exitValue() : output);
            }
            log.info("RCA patch passed git apply --check: repository={} patchChars={}", repository, patch.length());
            return new PatchCheck(true, "");
        } catch (IOException e) {
            log.warn("Could not validate RCA patch with Git; omitting patch: repository={} reason={}", repository, e.getMessage());
            return new PatchCheck(false, Objects.toString(e.getMessage(), "Could not run git apply --check"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("RCA patch Git validation was interrupted; omitting patch: repository={}", repository);
            return new PatchCheck(false, "git apply --check was interrupted");
        } finally {
            if (patchFile != null) try { Files.deleteIfExists(patchFile); } catch (IOException ignored) {}
            if (outputFile != null) try { Files.deleteIfExists(outputFile); } catch (IOException ignored) {}
        }
    }

    private record PatchCheck(boolean accepted, String diagnostic) {}

    private record Incident(String id, String repository, String rootCause, String resolution, String summary) {}
}
