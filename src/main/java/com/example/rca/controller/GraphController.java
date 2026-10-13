package com.example.rca.controller;

import com.example.rca.service.GraphStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Repository graph", description = "Read-only structural graph data for repository visualization")
public class GraphController {
    private static final int DEFAULT_NODE_LIMIT = 250;
    private static final int MAX_NODE_LIMIT = 500;
    private final GraphStore graph;

    public GraphController(GraphStore graph) { this.graph = graph; }

    @GetMapping("/repositories/graph")
    @Operation(summary = "Get repository graph data", description = "Returns a bounded node-link representation of the persisted Neo4j code graph for visualization. Nodes contain structural metadata only; source bodies, logs, and RCA text are excluded. A truncated flag indicates that node or relationship limits were reached.")
    public GraphStore.GraphSnapshot graph(
            @Parameter(description = "Repository name used during /repositories/sync", required = true, example = "petclinic")
            @RequestParam("repositoryName") String repositoryName,
            @Parameter(description = "Maximum nodes to return (1–500)", example = "250")
            @RequestParam(name = "limit", defaultValue = "250") int limit) {
        if (repositoryName.isBlank()) throw new IllegalArgumentException("repositoryName is required");
        if (limit < 1 || limit > MAX_NODE_LIMIT)
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_NODE_LIMIT);
        int relationshipLimit = Math.min(limit * 4, 2000);
        return graph.graph(repositoryName, limit, relationshipLimit);
    }
}