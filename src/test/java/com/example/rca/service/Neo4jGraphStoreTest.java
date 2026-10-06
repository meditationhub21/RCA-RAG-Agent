package com.example.rca.service;

import com.example.rca.model.CodeModels;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Neo4jGraphStoreTest {
    @Test void reportsExpectedNodeCountWhenDatabaseIsNotConfigured() {
        var type=new CodeModels.TypeInfo("Sample","class","demo","src/main/java/demo/Sample.java",1,
                List.of(),List.of(),List.of(),List.of(),List.of(new CodeModels.MethodInfo("run","void run()",2,2,"{}",List.of(),List.of())),"class Sample {}");
        var doc=new CodeModels.SourceDocument(type.file(),type.source(),"abc","JAVA");
        var snapshot=new CodeModels.RepositorySnapshot("demo",".",null,List.of(doc),List.of(type));
        var graph=new Neo4jGraphStore("","neo4j","neo4j");
        assertEquals(3,graph.upsert(snapshot));
        assertTrue(graph.context("demo","Sample","run").getFirst().contains("not configured"));
    }
}
