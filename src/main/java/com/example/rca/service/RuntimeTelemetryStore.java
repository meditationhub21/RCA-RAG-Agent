package com.example.rca.service;

import com.example.rca.model.RcaContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.time.DateTimeException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Holds the latest bounded metric snapshot supplied by Micrometer, OpenTelemetry, or another adapter. */
@Service
public class RuntimeTelemetryStore {
    private final Map<String,Snapshot> byRepository=new ConcurrentHashMap<>();

    public Snapshot accept(String repository,String source,String observedAt,Map<String,Double> metrics,Map<String,String> labels) {
        return accept(repository,source,observedAt,metrics,Map.of(),labels);
    }
    public Snapshot accept(String repository,String source,String observedAt,Map<String,Double> metrics,Map<String,String> units,Map<String,String> labels) {
        if(repository==null||repository.isBlank())throw new IllegalArgumentException("repositoryName is required");
        if(metrics==null||metrics.isEmpty())throw new IllegalArgumentException("metrics must contain at least one numeric measurement");
        if(metrics.size()>500)throw new IllegalArgumentException("A telemetry batch may contain at most 500 metrics");
        String time=observedAt==null||observedAt.isBlank()?Instant.now().toString():observedAt;
        try{Instant.parse(time);}catch(DateTimeException e){throw new IllegalArgumentException("observedAt must be an ISO-8601 instant",e);}
        String labelText=labels==null?"":labels.entrySet().stream().limit(30).map(e->e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining(","));
        var items=metrics.entrySet().stream().limit(500).filter(e->e.getKey()!=null&&!e.getKey().isBlank()&&e.getValue()!=null&&Double.isFinite(e.getValue()))
                .map(e->new RcaContext.RuntimeMetric(e.getKey(),e.getValue(),units==null?"":java.util.Objects.toString(units.get(e.getKey()),""),labelText,source==null?"unspecified":source,time)).toList();
        if(items.isEmpty())throw new IllegalArgumentException("No finite named metrics were supplied");
        var snapshot=new Snapshot(repository,source==null?"unspecified":source,time,items);
        byRepository.put(repository,snapshot);return snapshot;
    }

    public Snapshot latest(String repository){return byRepository.get(repository);}
    public Snapshot latestFresh(String repository,Duration maxAge){Snapshot s=latest(repository);if(s==null)return null;try{Instant observed=Instant.parse(s.observedAt()),now=Instant.now();return !observed.isAfter(now.plusSeconds(30))&&!observed.isBefore(now.minus(maxAge))?s:null;}catch(DateTimeException e){return null;}}
    public record Snapshot(String repository,String source,String observedAt,List<RcaContext.RuntimeMetric> metrics) {
        public Snapshot {metrics=List.copyOf(metrics);}
    }
}
