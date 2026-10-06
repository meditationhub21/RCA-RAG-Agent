package com.example.rca.controller;

import com.example.rca.model.ApiModels.SyncRequest;
import com.example.rca.model.ApiModels.SyncResponse;
import com.example.rca.service.RepositorySyncService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/repositories")
public class RepositoryController {
    private final RepositorySyncService sync;
    public RepositoryController(RepositorySyncService sync){this.sync=sync;}
    @PostMapping("/sync") public SyncResponse sync(@RequestBody SyncRequest request){return sync.sync(request.repositoryName(),request.sourceDirectory());}
}
