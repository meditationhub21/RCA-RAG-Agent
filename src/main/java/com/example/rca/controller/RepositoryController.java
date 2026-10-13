package com.example.rca.controller;

import com.example.rca.model.ApiModels.SyncRequest;
import com.example.rca.model.ApiModels.SyncResponse;
import com.example.rca.service.RepositorySyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/repositories")
@Tag(name = "Repositories", description = "Repository indexing and knowledge graph synchronization")
public class RepositoryController {
    private final RepositorySyncService sync;
    public RepositoryController(RepositorySyncService sync){this.sync=sync;}
    @PostMapping("/sync")
    @Operation(summary = "Synchronize a repository", description = "Scans source files, updates the persisted Neo4j code graph, and indexes source and method text in the configured vector store. Repeating a sync skips unchanged file embeddings and graph writes where possible.")
    public SyncResponse sync(@RequestBody SyncRequest request){return sync.sync(request.repositoryName(),request.sourceDirectory());}
}