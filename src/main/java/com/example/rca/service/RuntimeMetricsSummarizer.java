package com.example.rca.service;

import com.example.rca.model.RcaContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Interprets common metric semantics, independent of the monitoring vendor or broker client. */
final class RuntimeMetricsSummarizer {
    private RuntimeMetricsSummarizer(){}
    static List<String> findings(RcaContext context){
        var metrics=context.runtimeMetrics();var findings=new ArrayList<String>();
        for(var metric:metrics){
            String name=metric.name().toLowerCase(Locale.ROOT);double ratio=ratio(metric);
            if(name.contains("cpu")&&(name.endsWith("usage")||name.endsWith("utilization"))&&ratio>=.90)
                findings.add("CPU usage is high in the supplied snapshot: "+metric.name()+"="+metric.value()+metric.unit()+".");
            if(name.contains("connections.pending")&&metric.value()>0)
                findings.add("Connection-pool waiters are present: "+metric.name()+"="+metric.value()+".");
            if(name.endsWith(".active")){
                String prefix=name.substring(0,name.length()-".active".length());
                var max=metrics.stream().filter(m->m.name().equalsIgnoreCase(prefix+".max")).findFirst().orElse(null);
                if(max!=null&&max.value()>0&&metric.value()/max.value()>=.90)
                    findings.add("Active connections/tasks are at least 90% of the reported maximum for "+prefix+": "+metric.value()+"/"+max.value()+".");
            }
            if(name.endsWith(".used")){
                String prefix=name.substring(0,name.length()-".used".length());
                var max=metrics.stream().filter(m->m.name().equalsIgnoreCase(prefix+".max")).findFirst().orElse(null);
                if(max!=null&&max.value()>0&&metric.value()/max.value()>=.90)
                    findings.add("Reported used capacity is at least 90% of maximum for "+prefix+": "+metric.value()+"/"+max.value()+".");
            }
        }
        return findings.stream().distinct().limit(12).toList();
    }
    private static double ratio(RcaContext.RuntimeMetric m){String unit=m.unit()==null?"":m.unit().toLowerCase(Locale.ROOT);if(unit.contains("percent")||unit.equals("%"))return m.value()/100.0;return m.value();}
}
