package com.example.rca.model;

import java.util.List;

public final class CodeModels {
    private CodeModels() {}
    public record MethodInfo(String name, String signature, int line, int endLine, String body, List<String> calls,
                             List<String> thrownExceptions) {}
    public record TypeInfo(String name, String kind, String packageName, String file, int line,
                           List<String> annotations, List<String> extendsTypes, List<String> implementsTypes,
                           List<String> dependencies, List<MethodInfo> methods, String source) {}
    public record SourceDocument(String path, String content, String sha256, String kind) {}
    public record RepositorySnapshot(String name, String directory, String commit, List<SourceDocument> documents,
                                     List<TypeInfo> types,List<DependencyInfo> declaredDependencies) {
        public RepositorySnapshot(String name,String directory,String commit,List<SourceDocument> documents,List<TypeInfo> types) {
            this(name,directory,commit,documents,types,List.of());
        }
    }
    /** Dependency declared by a build file. It does not prove the library is enabled at runtime. */
    public record DependencyInfo(String group,String artifact,String version,String scope,String buildFile,String ecosystem) {
        public String coordinate(){return (group==null||group.isBlank()?"":group+":")+artifact+(version==null||version.isBlank()?"":"@"+version);}
    }
}
