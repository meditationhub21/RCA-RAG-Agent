package com.example.rca.service;

import com.example.rca.model.CodeModels;
import java.util.List;

public interface GraphStore extends AutoCloseable {
    int upsert(CodeModels.RepositorySnapshot snapshot);
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
