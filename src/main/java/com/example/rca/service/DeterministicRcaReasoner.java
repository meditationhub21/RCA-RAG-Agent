package com.example.rca.service;

import com.example.rca.model.CodeModels;
import com.example.rca.model.RcaAnalysis;
import com.example.rca.model.RcaContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Generic log-and-evidence synthesis. It makes no assumptions about a finite set of exception types. */
@Service
public class DeterministicRcaReasoner {
    private static final Pattern CAUSED_BY=Pattern.compile("(?m)^\\s*Caused by:\\s*([^\\r\\n]+)");

    public RcaAnalysis analyze(RcaContext context) {
        var evidence=context.evidence().stream().map(RcaContext.ContextEvidence::id).distinct().toList();
        String deepestCause=deepestCause(context.stackTrace());
        String errorMessage=clean(context.errorMessage());
        String location=context.location()==null?null:formatLocation(context.location());
        String statement=context.suspectedExpression();
        var facts=new ArrayList<String>();
        if(context.exceptionType()!=null)facts.add("The log reports "+context.exceptionType()+".");
        if(errorMessage!=null&&!errorMessage.equals(context.exceptionType()))facts.add("Reported message: "+errorMessage+".");
        if(deepestCause!=null)facts.add("Deepest logged cause: "+deepestCause+".");
        if(location!=null)facts.add("First repository-owned stack frame: "+location+".");
        if(statement!=null)facts.add("Source statement at the frame: `"+statement+"`.");
        if(context.variableContext()!=null)facts.add("Nearby source assignment: "+context.variableContext()+".");

        var matchingDependencies=RepositorySignals.matchingDependencies(context);
        if(!matchingDependencies.isEmpty())facts.add("Build manifests declare dependencies matching identifiers in this incident: "+matchingDependencies.stream()
                .map(d->d.coordinate()+" ("+d.buildFile()+")").distinct().limit(8).collect(Collectors.joining(", "))+". Declaration alone does not prove the dependency is active or causal.");
        var metrics=context.runtimeMetrics().stream().filter(RepositorySignals::runtimeMetricRelevant).limit(20).toList();
        if(!metrics.isEmpty()){
            facts.add("Runtime snapshot from "+metrics.getFirst().source()+" at "+metrics.getFirst().observedAt()+": "+metrics.stream()
                    .map(m->m.name()+"="+m.value()+m.unit()+(m.labels().isBlank()?"":" {"+m.labels()+"}")).collect(Collectors.joining(", "))+".");
            facts.addAll(RuntimeMetricsSummarizer.findings(context));
        }
        if(facts.isEmpty())facts.add("The uploaded log did not contain a recognizable exception, application frame, source location, or runtime signal.");

        String description=buildDescription(context,deepestCause,errorMessage,location,statement);
        String reasoning=String.join(" ",facts)+" These are observations from the supplied log and synchronized repository snapshot; they do not by themselves prove an unobserved runtime condition.";
        List<String> missing=new ArrayList<>(context.missingInformation());
        if(context.location()==null||context.location().file()==null)missing.add("No repository source frame could be resolved from the supplied stack trace.");
        if(deepestCause==null)missing.add("The log did not include a nested/root-cause exception; include the complete exception chain if available.");
        if(context.runtimeMetrics().isEmpty())missing.add("No fresh runtime metrics were supplied; CPU, memory, pool, and broker state cannot be assessed from build files.");
        if(context.declaredDependencies().isEmpty())missing.add("No dependency coordinates were discovered from supported Maven/Gradle build files.");
        missing=missing.stream().distinct().toList();
        String remediation=buildRemediation(context,deepestCause,statement);
        double confidence=context.location()!=null&&context.location().file()!=null&&statement!=null?(deepestCause==null?0.60:0.70):(deepestCause==null?0.25:0.40);
        return new RcaAnalysis(description,context.exceptionType(),statement,null,reasoning,evidence,remediation,confidence,missing,
                nextInvestigation(context,deepestCause),context.likelyIntroducingCommit(),null);
    }

    private static String buildDescription(RcaContext c,String cause,String message,String location,String expression){
        String where=location==null?"the log":"the repository frame at "+location;
        if(cause!=null)return "The exception chain reports `"+cause+"` as its deepest cause at "+where+(expression==null?".":" while executing `"+expression+"`.");
        String type=c.exceptionType()==null?"Failure":c.exceptionType();
        if(message!=null&&!message.equals(type))return type+" with message `"+message+"` is reported at "+where+(expression==null?".":" while executing `"+expression+"`.")+" The message narrows the failure symptom, but does not prove its upstream cause.";
        return type+" is reported at "+where+(expression==null?"; the supplied evidence does not establish a more specific cause.":" while executing `"+expression+"`; the log lacks enough cause detail to establish why it failed.");
    }
    private static String buildRemediation(RcaContext c,String cause,String expression){
        String operation=expression==null?"the failing operation":"`"+expression+"`";
        if(cause!=null)return "Use the reported root cause to correct the condition reaching "+operation+". Validate the relevant input/state at the nearest application boundary, return an appropriate domain error when it is invalid, and add a regression test that reproduces this log. Verify the fix against the same failure path.";
        return "Inspect the inputs, state, dependency configuration, and recent change reaching "+operation+". Once the cause is confirmed, correct the responsible condition at its source, preserve a clear failure response, and add a regression test. The current evidence is not sufficient to recommend a specific code change safely.";
    }
    private static List<String> nextInvestigation(RcaContext c,String cause){
        var next=new ArrayList<String>();
        if(c.location()!=null&&c.location().className()!=null)next.add("Trace the failing method's caller path and the values passed into it.");
        if(cause==null)next.add("Provide the full exception chain and the log lines immediately before the failure.");
        if(!c.declaredDependencies().isEmpty())next.add("Confirm which declared dependencies and profiles are active in the deployed build.");
        if(c.runtimeMetrics().isEmpty())next.add("If resource saturation is suspected, send fresh Micrometer/OpenTelemetry metrics through POST /repositories/telemetry.");
        if(c.likelyIntroducingCommit()!=null)next.add("Review the likely introducing commit and its diff.");
        if(next.isEmpty())next.add("Provide a complete stack trace and the synchronized source revision that produced it.");
        return next.stream().distinct().toList();
    }
    private static String deepestCause(String trace){if(trace==null||trace.isBlank())return null;Matcher m=CAUSED_BY.matcher(trace);String cause=null;while(m.find())cause=m.group(1).trim();return cause;}
    private static String clean(String error){if(error==null||error.isBlank())return null;return error.trim().replaceAll("[\\r\\n]+"," ");}
    private static String formatLocation(RcaContext.FailureLocation p){return (p.className()==null?"":p.className()+".")+(p.method()==null?"":p.method()+" ")+"at "+p.file()+":"+p.line();}
}
