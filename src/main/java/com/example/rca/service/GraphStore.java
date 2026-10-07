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
    default void storeIncident(String repository, String caseId, String error, String affectedFile,
                               String affectedClass, String affectedMethod, String rootCause,
                               String evidence, String resolution, double confidence) {}
    default List<StoredIncident> incidents(String repository, List<String> caseIds, int limit) { return List.of(); }
    @Override default void close() {}
    record MethodContext(String containingClass,String file,Integer line,List<String> callers,List<String> callees,
                         List<String> dependencies,List<String> exceptions,List<String> relatedTests,
                         List<String> relatedCommits) {}
    record StoredIncident(String caseId,String rootCause,String resolution) {}
}
