package com.example.rca.service;

import com.example.rca.model.CodeModels;
import java.util.List;
import java.util.Optional;
import java.util.Map;

public interface GraphStore extends AutoCloseable {
    int upsert(CodeModels.RepositorySnapshot snapshot);
    /** Persisted source root used to rebuild the in-memory scan after an app restart. */
    default Optional<String> repositoryDirectory(String repository) { return Optional.empty(); }
    default Optional<String> repositoryCommit(String repository) { return Optional.empty(); }
    default Map<String,String> repositoryFileHashes(String repository) { return Map.of(); }
    /** Persist lightweight repository metadata without rewriting indexed graph entities. */
    default void updateRepositoryMetadata(CodeModels.RepositorySnapshot snapshot) {}
    List<String> context(String repository, String qualifiedClass, String method);
    default MethodContext methodContext(String repository,String className,String method) {
        return new MethodContext(className,null,null,List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    }
    /** Return a bounded structural node-link view, excluding source and incident text. */
    default GraphSnapshot graph(String repository, int nodeLimit, int relationshipLimit) {
        return new GraphSnapshot(repository, List.of(), List.of(), false);
    }
    default void storeIncident(String repository, String caseId, String error, String affectedFile,
                               String affectedClass, String affectedMethod, String rootCause,
                               String evidence, String resolution, double confidence, String proposedPatch) {}
    default Optional<String> incidentPatch(String repository, String caseId) { return Optional.empty(); }
    default List<StoredIncident> incidents(String repository, List<String> caseIds, int limit) { return List.of(); }
    @Override default void close() {}
    record MethodContext(String containingClass,String file,Integer line,List<String> callers,List<String> callees,
                         List<String> dependencies,List<String> exceptions,List<String> relatedTests,
                         List<String> relatedCommits) {}
    record StoredIncident(String caseId,String rootCause,String resolution) {}
    record GraphSnapshot(String repository, List<GraphNode> nodes, List<GraphRelationship> relationships, boolean truncated) {
        public GraphSnapshot { nodes = List.copyOf(nodes); relationships = List.copyOf(relationships); }
    }
    record GraphNode(String id, List<String> labels, Map<String,Object> properties) {
        public GraphNode { labels = List.copyOf(labels); properties = Map.copyOf(properties); }
    }
    record GraphRelationship(String id, String source, String type, String target) {}
}