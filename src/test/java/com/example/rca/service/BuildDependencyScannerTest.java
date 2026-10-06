package com.example.rca.service;

import com.example.rca.model.CodeModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuildDependencyScannerTest {
    @Test void discoversMavenGradleAndVersionCatalogCoordinates() {
        var docs=List.of(
                new CodeModels.SourceDocument("pom.xml","""
                        <project><properties><kafka.version>3.8.1</kafka.version></properties><dependencies>
                        <dependency><groupId>org.springframework.kafka</groupId><artifactId>spring-kafka</artifactId><version>${kafka.version}</version></dependency>
                        <dependency><groupId>com.zaxxer</groupId><artifactId>HikariCP</artifactId></dependency>
                        </dependencies></project>
                        ""","","CONFIG"),
                new CodeModels.SourceDocument("build.gradle.kts","dependencies { implementation(libs.spring.boot.starter.data.redis) }","","CONFIG"),
                new CodeModels.SourceDocument("gradle/libs.versions.toml","[libraries]\nspring-boot-starter-data-redis = { module = \"org.springframework.boot:spring-boot-starter-data-redis\" }","","CONFIG"));
        var found=new BuildDependencyScanner().scan(docs);
        assertTrue(found.stream().anyMatch(d->d.group().equals("org.springframework.kafka")&&d.version().equals("3.8.1")));
        assertTrue(found.stream().anyMatch(d->d.artifact().equals("HikariCP")));
        assertTrue(found.stream().anyMatch(d->d.artifact().equals("spring-boot-starter-data-redis")),found.toString());
        var incident=new com.example.rca.model.RcaContext("demo","",null,found,List.of(),
                "org.apache.kafka.common.KafkaException: broker unavailable","KafkaException","at org.apache.kafka.clients.NetworkClient.poll(NetworkClient.java:100)",
                null,null,null,null,null,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),null,List.of(),List.of(),List.of(),List.of(),"HIGH",List.of());
        var analysis=new DeterministicRcaReasoner().analyze(incident);
        assertTrue(analysis.reasoning().contains("Build manifests declare dependencies matching identifiers"));
        assertEquals("spring-kafka",RepositorySignals.matchingDependencies(incident).getFirst().artifact());
    }
}
