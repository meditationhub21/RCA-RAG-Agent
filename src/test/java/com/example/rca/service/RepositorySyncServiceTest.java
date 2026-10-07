package com.example.rca.service;

import com.example.rca.parser.JavaAstParser;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RepositorySyncServiceTest {
    @Test void indexesJavaAndCountsFirstSyncAsChanged() throws Exception {
        var directory=Files.createTempDirectory("rca-sync-test");
        Files.writeString(directory.resolve("Demo.java"),"package demo; class Demo { void run() {} }");
        var graph=mock(GraphStore.class); when(graph.upsert(any())).thenReturn(3);
        var service=new RepositorySyncService(new JavaAstParser(),graph,new HashVectorStore(),JsonMapper.builder().build(),"",directory.resolve("index").toString());
        var response=service.sync("demo",directory.toString());
        assertEquals("SYNCED",response.status()); assertEquals(1,response.filesScanned()); assertEquals(1,response.filesChanged());
        assertTrue(service.latest("demo").isPresent());
    }

    @Test void skipsGraphRewriteWhenFilesAndCommitAreUnchanged() throws Exception {
        var directory=Files.createTempDirectory("rca-sync-stable-test");
        Files.writeString(directory.resolve("Demo.java"),"package demo; class Demo { void run() {} }");
        var graph=mock(GraphStore.class);
        when(graph.upsert(any())).thenReturn(3);
        when(graph.repositoryDirectory("demo")).thenReturn(Optional.of(directory.toString()));
        when(graph.repositoryCommit("demo")).thenReturn(Optional.empty());
        var service=new RepositorySyncService(new JavaAstParser(),graph,new HashVectorStore(),JsonMapper.builder().build(),"",directory.resolve("index").toString());
        service.sync("demo",directory.toString());
        var second=service.sync("demo",directory.toString());
        assertEquals(0,second.filesChanged());
        assertEquals(0,second.graphNodesUpdated());
        verify(graph,times(1)).upsert(any());
    }

    @Test void restoresSnapshotFromPersistedRepositoryDirectoryAfterRestart() throws Exception {
        var directory=Files.createTempDirectory("rca-sync-restore-test");
        Files.writeString(directory.resolve("Demo.java"),"package demo; class Demo { void run() {} }");
        var graph=mock(GraphStore.class);
        when(graph.repositoryDirectory("demo")).thenReturn(Optional.of(directory.toString()));
        var restartedService=new RepositorySyncService(new JavaAstParser(),graph,new HashVectorStore(),JsonMapper.builder().build(),"",directory.resolve("new-index").toString());
        var restored=restartedService.latest("demo");
        assertTrue(restored.isPresent());
        assertEquals(1,restored.orElseThrow().documents().size());
        verify(graph,never()).upsert(any());
    }

    @Test void usesPersistentGraphFileHashesWhenLocalManifestIsMissing() throws Exception {
        var directory=Files.createTempDirectory("rca-sync-hash-restore-test");
        var source=directory.resolve("Demo.java");
        String content="package demo; class Demo { void run() {} }";
        Files.writeString(source,content);
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var graph=mock(GraphStore.class);
        when(graph.repositoryDirectory("demo")).thenReturn(Optional.of(directory.toString()));
        when(graph.repositoryFileHashes("demo")).thenReturn(java.util.Map.of("Demo.java",hash));
        var service=new RepositorySyncService(new JavaAstParser(),graph,new HashVectorStore(),JsonMapper.builder().build(),"",directory.resolve("missing-index").toString());
        var result=service.sync("demo",directory.toString());
        assertEquals(0,result.filesChanged());
        assertEquals(0,result.embeddingsUpdated());
        assertEquals(0,result.graphNodesUpdated());
        verify(graph,never()).upsert(any());
    }
}
