package com.example.rca.service;

import com.example.rca.model.CodeModels;
import com.example.rca.model.RcaContext;
import com.example.rca.parser.JavaAstParser;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RcaServiceTest {
    @Test void explainsAnArbitraryNestedCauseUsingTheApplicationFrameAndSource() {
        var context=context("Request failed","ClientException",
                "at example.OrderService.submit(OrderService.java:42)\nCaused by: java.net.SocketTimeoutException: read timed out",
                "client.send(order);",List.of(),List.of());
        var result=new DeterministicRcaReasoner().analyze(context);
        assertTrue(result.description().contains("java.net.SocketTimeoutException: read timed out"));
        assertTrue(result.description().contains("Service.run"));
        assertTrue(result.reasoning().contains("Source statement at the frame"));
        assertTrue(result.fixRecommendation().contains("regression test"));
    }

    @Test void correlatesArbitraryDeclaredDependenciesWithoutPerLibraryHandlers() {
        var dependencies=List.of(
                new CodeModels.DependencyInfo("org.springframework.kafka","spring-kafka","3.8.1","runtime","pom.xml","MAVEN"),
                new CodeModels.DependencyInfo("org.springframework.boot","spring-boot-starter-data-redis","3.5.0","compile","build.gradle.kts","GRADLE"));
        var context=context("Kafka client failed","org.apache.kafka.common.KafkaException",
                "at org.apache.kafka.clients.NetworkClient.poll(NetworkClient.java:100)\nat app.Consumer.handle(Consumer.java:20)",
                "consumer.poll();",dependencies,List.of());
        var result=new DeterministicRcaReasoner().analyze(context);
        assertTrue(result.reasoning().contains("spring-kafka"));
        assertFalse(result.reasoning().contains("spring-boot-starter-data-redis"));
        assertTrue(result.reasoning().contains("does not prove the dependency is active or causal"));
    }

    @Test void keepsUnprovenCauseUnknownRatherThanGuessingFromAnExceptionName() {
        var context=context("Unexpected failure","com.example.CustomException","at example.Job.run(Job.java:8)",
                "worker.process(message);",List.of(),List.of());
        var result=new DeterministicRcaReasoner().analyze(context);
        assertTrue(result.description().contains("does not prove its upstream cause"));
        assertTrue(result.missingInformation().stream().anyMatch(s->s.contains("root-cause exception")));
        assertTrue(result.confidence()<0.7);
    }

    @Test void recognizesFreshTelemetryAsContextWithoutAttributingCausality() {
        var metrics=List.of(new RcaContext.RuntimeMetric("process.cpu.usage",0.97,"ratio","","micrometer","now"));
        var context=context("Failure","CustomException","at app.Work.run(Work.java:4)","work();",List.of(),metrics);
        var result=new DeterministicRcaReasoner().analyze(context);
        assertTrue(result.reasoning().contains("CPU usage is high"));
        assertTrue(result.reasoning().contains("do not by themselves prove"));
    }

    @Test void uploadedNpeLogReturnsSourceAndReasoningWithoutAnNpeSpecificHandler() throws Exception {
        var directory=Files.createTempDirectory("rca-general-log-test");
        Files.writeString(directory.resolve("RcaDemoController.java"), "package demo;\nclass RcaDemoController {\n  String trigger() {\n    String ownerName = null;\n    return ownerName.trim();\n  }\n}");
        var graph=mock(GraphStore.class);when(graph.upsert(any())).thenReturn(1);
        var vectors=new HashVectorStore();
        var repository=new RepositorySyncService(new JavaAstParser(),graph,vectors,JsonMapper.builder().build(),"",directory.resolve("index").toString());
        repository.sync("demo",directory.toString());
        var service=new RcaService(repository,graph,vectors,new RcaContextBuilder(graph,new GitAnalysisService(),vectors),
                new DeterministicRcaReasoner(),(incidentContext,evidenceAnalysis)->evidenceAnalysis,JsonMapper.builder().build());
        var response=service.investigate("demo","java.lang.NullPointerException: Cannot invoke trim() because ownerName is null",
                "at demo.RcaDemoController.trigger(RcaDemoController.java:5)");
        assertEquals(5,response.rootCause().line());
        assertTrue(response.rootCause().description().contains("ownerName is null"));
        assertTrue(response.reasoning().contains("return ownerName.trim();"));
        assertTrue(response.fixRecommendation().contains("not sufficient to recommend a specific code change safely"));
    }

    private static RcaContext context(String error,String type,String trace,String expression,
                                      List<CodeModels.DependencyInfo> dependencies,List<RcaContext.RuntimeMetric> metrics) {
        var evidence=List.of(new RcaContext.ContextEvidence("log","ERROR_LOG",error,null,null),
                new RcaContext.ContextEvidence("stack","STACK_TRACE",trace,"Service.java",42),
                new RcaContext.ContextEvidence("source","SOURCE_CODE",expression,"Service.java",42));
        return new RcaContext("demo","",null,dependencies,metrics,error,type,trace,
                new RcaContext.FailureLocation("Service.java","Service","run",42),expression,null,expression,expression,
                List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),null,List.of(),evidence,List.of(),List.of(),"HIGH",List.of());
    }
}
