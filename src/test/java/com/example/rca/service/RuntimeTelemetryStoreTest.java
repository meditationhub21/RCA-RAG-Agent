package com.example.rca.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeTelemetryStoreTest {
    @Test void acceptsGenericMetricsAndKeepsLatestSnapshot() {
        var store=new RuntimeTelemetryStore();
        var observed=java.time.Instant.now().toString();
        var snapshot=store.accept("petclinic","micrometer",observed,
                Map.of("process.cpu.usage",0.84,"jvm.memory.used",7000000.0,"hikaricp.connections.pending",3.0),
                Map.of("process.cpu.usage","ratio","jvm.memory.used","bytes"),Map.of("instance","petclinic-1"));
        assertEquals(3,snapshot.metrics().size());
        assertEquals("micrometer",store.latest("petclinic").source());
        assertEquals(observed,store.latest("petclinic").observedAt());
        assertEquals("ratio",store.latest("petclinic").metrics().stream().filter(m->m.name().equals("process.cpu.usage")).findFirst().orElseThrow().unit());
        assertNotNull(store.latestFresh("petclinic",java.time.Duration.ofMinutes(1)));
        store.accept("old","micrometer","2020-01-01T00:00:00Z",Map.of("cpu",1.0),Map.of());
        assertNull(store.latestFresh("old",java.time.Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class,()->store.accept("petclinic","test",null,Map.of("bad",Double.NaN),Map.of()));
    }

    @Test void summarizesCpuMemoryAndPoolPressureUsingMetricSemantics() {
        var metrics=java.util.List.of(
                new com.example.rca.model.RcaContext.RuntimeMetric("process.cpu.usage",0.95,"ratio","","micrometer","now"),
                new com.example.rca.model.RcaContext.RuntimeMetric("jvm.memory.used",950,"bytes","","micrometer","now"),
                new com.example.rca.model.RcaContext.RuntimeMetric("jvm.memory.max",1000,"bytes","","micrometer","now"),
                new com.example.rca.model.RcaContext.RuntimeMetric("hikaricp.connections.active",19,"","","micrometer","now"),
                new com.example.rca.model.RcaContext.RuntimeMetric("hikaricp.connections.max",20,"","","micrometer","now"),
                new com.example.rca.model.RcaContext.RuntimeMetric("hikaricp.connections.pending",2,"","","micrometer","now"));
        var context=new com.example.rca.model.RcaContext("demo","",null,java.util.List.of(),metrics,"failure","ExampleException","",
                null,null,null,null,null,java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of(),null,
                java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of(),"HIGH",java.util.List.of());
        var findings=RuntimeMetricsSummarizer.findings(context);
        assertEquals(4,findings.size());
        assertTrue(findings.stream().anyMatch(s->s.contains("CPU usage is high")));
        assertTrue(findings.stream().anyMatch(s->s.contains("used capacity")));
        assertTrue(findings.stream().anyMatch(s->s.contains("Active connections/tasks")));
        assertTrue(findings.stream().anyMatch(s->s.contains("waiters")));
    }
}
