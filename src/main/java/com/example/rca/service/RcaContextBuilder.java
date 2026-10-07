package com.example.rca.service;

import com.example.rca.model.ApiModels.Evidence;
import com.example.rca.model.CodeModels;
import com.example.rca.model.RcaContext;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class RcaContextBuilder {
    private static final Logger log = LoggerFactory.getLogger(RcaContextBuilder.class);
    private static final Pattern FRAME = Pattern.compile("at\\s+([\\w.$]+)\\.([\\w$<>]+)\\(([^():]+\\.java):(\\d+)\\)");
    private static final int MAX_SOURCE_CHARS = 12000;
    private final GraphStore graph;
    private final GitAnalysisService git;
    private final VectorStore vectors;
    private final RuntimeTelemetryStore telemetry;
    private final java.time.Duration telemetryMaxAge;

    @Autowired
    public RcaContextBuilder(GraphStore graph, GitAnalysisService git, VectorStore vectors,RuntimeTelemetryStore telemetry,
                             @Value("${rca.telemetry.max-age:PT10M}") java.time.Duration telemetryMaxAge) {
        this.graph = graph; this.git = git; this.vectors = vectors;this.telemetry=telemetry;this.telemetryMaxAge=telemetryMaxAge;
    }
    public RcaContextBuilder(GraphStore graph,GitAnalysisService git,VectorStore vectors) {
        this(graph,git,vectors,new RuntimeTelemetryStore(),java.time.Duration.ofMinutes(10));
    }

    public RcaContext build(String repository, String error, String trace, CodeModels.RepositorySnapshot snapshot,
                            Map<String, String> priorIncidents) {
        long contextStarted = System.nanoTime();
        Matcher frame = FRAME.matcher(Objects.toString(trace, ""));
        String qualified = null, method = null, fileName = null; Integer line = null;
        String firstQualified = null, firstMethod = null, firstFile = null; Integer firstLine = null;
        while (frame.find()) {
            String candidateFile = frame.group(3);
            boolean belongsToRepository = snapshot.documents().stream().anyMatch(d -> d.path().endsWith("/" + candidateFile) || d.path().equals(candidateFile));
            if (firstFile == null) { firstQualified=frame.group(1);firstMethod=frame.group(2);firstFile=candidateFile;firstLine=Integer.valueOf(frame.group(4)); }
            if (belongsToRepository) { qualified=frame.group(1);method=frame.group(2);fileName=candidateFile;line=Integer.valueOf(frame.group(4));break; }
        }
        if(fileName==null){qualified=firstQualified;method=firstMethod;fileName=firstFile;line=firstLine;}
        String className = qualified == null ? null : qualified.substring(qualified.lastIndexOf('.') + 1);
        final String frameFile = fileName, frameMethod = method, frameClass = className;
        CodeModels.SourceDocument doc = frameFile == null ? null : snapshot.documents().stream()
                .filter(d -> d.path().endsWith("/" + frameFile) || d.path().equals(frameFile)).findFirst().orElse(null);
        var evidence = new ArrayList<RcaContext.ContextEvidence>();
        add(evidence,"error-log","ERROR_LOG",clip(Objects.toString(error,"No error message supplied."),4000),null,null);
        add(evidence, "stack", "STACK_TRACE", (frameFile == null ? "No Java stack frame with a source line was found." :
                "Failure frame points to " + qualified + "." + method + " at " + fileName + ":" + line)+
                "\nStack trace excerpt:\n"+clip(Objects.toString(trace,""),8000), fileName, line);
        String source = null, methodSource = null, suspectedExpression = null, variableContext = null;
        if (doc != null && line != null) {
            String[] lines = doc.content().split("\\R", -1);
            int start = Math.max(0, line - 6), end = Math.min(lines.length, line + 5);
            source = String.join("\n", Arrays.copyOfRange(lines, start, end));
            if (source.length() > MAX_SOURCE_CHARS) source = source.substring(0, MAX_SOURCE_CHARS);
            add(evidence, "source", "SOURCE_CODE", "Source around the reported line:\n" + source, doc.path(), line);
            CodeModels.TypeInfo type = snapshot.types().stream().filter(t -> t.file().equals(doc.path()) && (frameClass == null || t.name().equals(frameClass))).findFirst().orElse(null);
            CodeModels.MethodInfo methodInfo = type == null ? null : type.methods().stream().filter(m -> frameMethod == null || m.name().equals(frameMethod)).findFirst().orElse(null);
            if (methodInfo != null) {
                methodSource = clip(methodInfo.body(),8000);
                add(evidence, "structure", "CODE_STRUCTURE", clip("Parsed method " + methodInfo.signature() + "; calls " + methodInfo.calls() + "; declares exceptions " + methodInfo.thrownExceptions(),3000), doc.path(), methodInfo.line());
                suspectedExpression = expressionOnLine(doc.content(), line);
                variableContext = assignmentBefore(doc.content(), line, receiverVariable(suspectedExpression));
            }
        }
        long graphStarted = System.nanoTime();
        GraphStore.MethodContext graphContext = graph.methodContext(repository, className, method);
        if (graphContext == null) graphContext = new GraphStore.MethodContext(className, fileName, line,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        graph.context(repository, className, method).forEach(s -> add(evidence, "graph-" + evidence.size(), "CODE_GRAPH", s, null, null));
        long graphMs = (System.nanoTime() - graphStarted) / 1_000_000;
        log.info("RCA context graph lookup complete: repository={} elapsedMs={}", repository, graphMs);
        long gitStarted = System.nanoTime();
        GitAnalysisService.GitContext gitContext = git.inspect(snapshot, doc == null ? fileName : doc.path(), line);
        long gitMs = (System.nanoTime() - gitStarted) / 1_000_000;
        log.info("RCA context Git lookup complete: repository={} historyItems={} diffItems={} elapsedMs={}", repository,
                gitContext.history().size(), gitContext.diffs().size(), gitMs);
        if (!gitContext.history().isEmpty()) add(evidence, "git-history", "GIT_HISTORY", String.join("\n", gitContext.history()), doc == null ? fileName : doc.path(), line);
        if (!gitContext.diffs().isEmpty()) add(evidence, "git-diff", "GIT_DIFF", String.join("\n", gitContext.diffs()), doc == null ? fileName : doc.path(), line);
        String query = error + " " + Objects.toString(trace, "");
        long retrievalStarted = System.nanoTime();
        var semantic = vectors.search(query, 8, repository).stream()
                .map(m -> m.text().substring(0, Math.min(m.text().length(), 3000))).toList();
        semantic.forEach(s -> add(evidence, "semantic-" + evidence.size(), "SEMANTIC_CODE", s, null, null));
        var similar = vectors.search(query, 5, "incident").stream()
                .map(m -> priorIncidents.get(m.id())).filter(Objects::nonNull).toList();
        for (int i = 0; i < similar.size(); i++) add(evidence, "similar-rca-" + i, "SIMILAR_RCA", similar.get(i), null, null);
        long retrievalMs = (System.nanoTime() - retrievalStarted) / 1_000_000;
        log.info("RCA context vector retrieval complete: repository={} semanticMatches={} incidentMatches={} elapsedMs={}",
                repository, semantic.size(), similar.size(), retrievalMs);
        var config = snapshot.documents().stream().filter(d -> d.kind().equals("CONFIG"))
                .map(d -> d.path() + "\n" + sanitizeConfig(d.content())).map(s -> s.substring(0,Math.min(s.length(),2000))).limit(8).toList();
        if (!config.isEmpty()) add(evidence, "configuration", "CONFIGURATION", String.join("\n---\n", config), null, null);
        var declaredDependencies=snapshot.declaredDependencies().stream().limit(300).toList();
        if(!declaredDependencies.isEmpty())add(evidence,"build-dependencies","BUILD_DEPENDENCIES",clip(declaredDependencies.stream().map(CodeModels.DependencyInfo::coordinate).distinct().collect(java.util.stream.Collectors.joining("\n")),12000),null,null);
        var telemetrySnapshot=telemetry.latestFresh(repository,telemetryMaxAge);
        var runtimeMetrics=telemetrySnapshot==null?List.<RcaContext.RuntimeMetric>of():telemetrySnapshot.metrics().stream().limit(120).toList();
        if(!runtimeMetrics.isEmpty())add(evidence,"runtime-metrics","RUNTIME_METRICS","source="+telemetrySnapshot.source()+" observedAt="+telemetrySnapshot.observedAt()+"\n"+
                clip(runtimeMetrics.stream().map(m->m.name()+"="+m.value()+m.unit()+(m.labels().isBlank()?"":" labels{"+m.labels()+"}")).collect(java.util.stream.Collectors.joining("\n")),12000),null,null);
        var missing = new ArrayList<String>();
        if (trace == null || trace.isBlank()) missing.add("Full stack trace was not supplied.");
        if (doc == null) missing.add("The reported source file was not found in the synchronized repository snapshot.");
        if (snapshot.commit() == null) missing.add("Repository Git commit could not be determined.");
        if(declaredDependencies.isEmpty())missing.add("No Maven/Gradle dependency coordinates were discovered; check for an unsupported build format or version catalog.");
        if(runtimeMetrics.isEmpty())missing.add("No runtime telemetry was supplied. Build files cannot provide live CPU, memory, pool saturation, or broker/cache health values.");
        if (graphContext.callers().isEmpty()) missing.add("No caller relationship was found in the code graph.");
        String quality = doc != null && line != null && methodSource != null ? "HIGH: stack frame, source and parsed method are available" :
                doc != null ? "MEDIUM: source is available but method-level evidence is incomplete" : "LOW: source location could not be resolved";
        RcaContext context = new RcaContext(repository, snapshot.directory(), snapshot.commit(),declaredDependencies,runtimeMetrics,clip(error,2000), exceptionName(error), clip(trace,16000),
                new RcaContext.FailureLocation(doc == null ? fileName : doc.path(), className, method, line), suspectedExpression,
                clip(variableContext,1000), source, methodSource, bounded(graphContext.callers()), bounded(graphContext.callees()), bounded(graphContext.dependencies()),
                bounded(graphContext.exceptions()), bounded(graphContext.relatedTests()), gitContext.history(), gitContext.diffs(),
                gitContext.likelyIntroducingCommit(), config, evidence, semantic, similar, quality, missing);
        log.info("RCA context construction complete: repository={} evidenceItems={} totalMs={}", repository, evidence.size(),
                (System.nanoTime() - contextStarted) / 1_000_000);
        return context;
    }

    private static String exceptionName(String error) {
        Matcher m = Pattern.compile("([\\w.$]*(?:Exception|Error))").matcher(error);
        return m.find() ? m.group(1) : null;
    }
    private static void add(List<RcaContext.ContextEvidence> out, String id, String type, String details, String file, Integer line) {
        out.add(new RcaContext.ContextEvidence(id, type, details, file, line));
    }
    private static String expressionOnLine(String source, int line) {
        String[] lines = source.split("\\R", -1);
        if (line < 1 || line > lines.length) return null;
        String s = lines[line - 1].trim().replaceAll("\\s+", " ");
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
    private static String receiverVariable(String expression) {
        if (expression == null) return null;
        Matcher m = Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*\\.").matcher(expression);
        return m.find() ? m.group(1) : null;
    }
    private static String assignmentBefore(String source, int line, String variable) {
        if (variable == null) return null;
        String[] lines = source.split("\\R", -1);
        Pattern p = Pattern.compile("\\b" + Pattern.quote(variable) + "\\s*=\\s*(null|new\\s+[\\w.$<>]+\\s*\\([^;]*\\)|[^;]+);?");
        for (int i = Math.min(line - 2, lines.length - 1); i >= 0; i--) {
            Matcher m = p.matcher(lines[i]);
            if (m.find()) return "line " + (i + 1) + ": " + lines[i].trim();
        }
        return null;
    }
    private static String sanitizeConfig(String content) {
        return content.lines().limit(100).map(line -> line.replaceAll(
                "(?i)(password|passwd|secret|token|api[_-]?key|client[_-]?secret|credentials)(\\s*[:=]\\s*|\\s+).*$", "$1$2[REDACTED]"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }
    private static String clip(String value,int max) { return value==null?null:value.substring(0,Math.min(value.length(),max)); }
    private static List<String> bounded(List<String> values) { return values==null?List.of():values.stream().limit(20).map(v->clip(v,1000)).toList(); }
}
