package com.example.rca.service;

import com.example.rca.parser.JavaAstParser;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
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
}
