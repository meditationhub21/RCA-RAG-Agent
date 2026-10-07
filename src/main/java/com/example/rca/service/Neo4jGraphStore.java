package com.example.rca.service;

import com.example.rca.model.CodeModels;
import org.neo4j.driver.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.*;

@Component
public class Neo4jGraphStore implements GraphStore {
    private static final Logger log=LoggerFactory.getLogger(Neo4jGraphStore.class);
    private final Driver driver;
    public Neo4jGraphStore(@Value("${rca.neo4j.uri:}") String uri,
                           @Value("${rca.neo4j.username:neo4j}") String user,
                           @Value("${rca.neo4j.password:neo4j}") String password) {
        this.driver = uri == null || uri.isBlank() ? null : GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        if(this.driver==null) log.warn("Neo4j URI is empty; graph writes are disabled and only estimated counts will be returned");
        else log.info("Neo4j driver configured for {}",uri);
    }
    @Override public int upsert(CodeModels.RepositorySnapshot s) {
        if (driver == null) return s.documents().size() + s.types().size() + s.types().stream().mapToInt(t -> t.methods().size()).sum();
        log.info("Writing repository graph to Neo4j: repository={} files={} types={} methods={}",s.name(),s.documents().size(),s.types().size(),s.types().stream().mapToInt(t->t.methods().size()).sum());
        try (var session=driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (r:Repository {name:$repo}) SET r.directory=$dir,r.commit=$commit", Map.of("repo",s.name(),"dir",s.directory(),"commit",Objects.toString(s.commit(),"")));
                tx.run("MERGE (a:Application {name:$repo}) MERGE (r:Repository {name:$repo}) MERGE (a)-[:CONTAINS]->(r)",Map.of("repo",s.name()));
                var paths=s.documents().stream().map(CodeModels.SourceDocument::path).toList();
                tx.run("MATCH (f:File {repository:$repo}) WHERE NOT f.path IN $paths DETACH DELETE f",Map.of("repo",s.name(),"paths",paths));
                tx.run("MATCH (n {repository:$repo}) WHERE (n:Class OR n:Interface OR n:Method OR n:Test OR n:API) AND NOT EXISTS { MATCH (f:File {repository:$repo})-[:CONTAINS*]->(n) } DETACH DELETE n",Map.of("repo",s.name()));
                for (var doc:s.documents()) {
                    var pkg = s.types().stream().filter(t -> t.file().equals(doc.path())).map(CodeModels.TypeInfo::packageName).findFirst().orElse("");
                    tx.run("MATCH (f:File {repository:$repo,path:$path}) WHERE f.hash <> $hash OPTIONAL MATCH (f)-[:CONTAINS]->(c)-[:HAS_METHOD]->(m) DETACH DELETE m,c,f",Map.of("repo",s.name(),"path",doc.path(),"hash",doc.sha256()));
                    tx.run("MATCH (r:Repository {name:$repo}) MERGE (p:Package {name:$pkg,repository:$repo}) MERGE (f:File {path:$path,repository:$repo}) SET f.hash=$hash,f.kind=$kind MERGE (r)-[:CONTAINS]->(p) MERGE (p)-[:CONTAINS]->(f)", Map.of("repo",s.name(),"pkg",pkg,"path",doc.path(),"hash",doc.sha256(),"kind",doc.kind()));
                }
                for(var t:s.types()) {
                    var label=t.kind().equals("interface")?"Interface":"Class";
                    tx.run("MATCH (f:File {path:$path,repository:$repo}) MERGE (c:"+label+" {name:$name,repository:$repo}) SET c.package=$pkg,c.line=$line,c.source=$source MERGE (f)-[:CONTAINS]->(c)", Map.of("path",t.file(),"repo",s.name(),"name",t.name(),"pkg",t.packageName(),"line",t.line(),"source",t.source()));
                    for(var m:t.methods()) {
                        tx.run("MATCH (c {name:$name,repository:$repo}) MERGE (m:Method {signature:$sig,repository:$repo}) SET m.name=$method,m.owner=$name,m.file=$file,m.line=$line,m.body=$body MERGE (c)-[:HAS_METHOD]->(m)", Map.of("name",t.name(),"repo",s.name(),"sig",t.name()+"#"+m.signature(),"method",m.name(),"file",t.file(),"line",m.line(),"body",m.body()));
                        for(var exception:m.thrownExceptions()) tx.run("MATCH (m:Method {signature:$sig,repository:$repo}) MERGE (e:Exception {name:$exception,repository:$repo}) MERGE (m)-[:THROWS]->(e)",Map.of("sig",t.name()+"#"+m.signature(),"repo",s.name(),"exception",exception));
                        for(var call:m.calls()) tx.run("MATCH (m:Method {signature:$sig,repository:$repo}) MATCH (target:Method {name:$target,repository:$repo}) WHERE target.signature <> m.signature MERGE (m)-[:CALLS]->(target)",Map.of("sig",t.name()+"#"+m.signature(),"repo",s.name(),"target",call));
                    }
                    for(var parent:t.extendsTypes()) tx.run("MATCH (c {name:$name,repository:$repo}) MERGE (p:Class {name:$parent,repository:$repo}) MERGE (c)-[:EXTENDS]->(p)", Map.of("name",t.name(),"repo",s.name(),"parent",parent));
                    for(var iface:t.implementsTypes()) tx.run("MATCH (c {name:$name,repository:$repo}) MERGE (i:Interface {name:$iface,repository:$repo}) MERGE (c)-[:IMPLEMENTS]->(i)", Map.of("name",t.name(),"repo",s.name(),"iface",iface));
                    for(var dependency:t.dependencies()) tx.run("MATCH (c {name:$name,repository:$repo}) MERGE (d:Class {name:$dependency,repository:$repo}) MERGE (c)-[:DEPENDS_ON]->(d)",Map.of("name",t.name(),"repo",s.name(),"dependency",dependency));
                    if(t.file().contains("src/test/java")||t.file().contains("/test/")) {
                        String production=t.name().replaceFirst("Test$|Tests$","");
                        tx.run("MATCH (f:File {path:$path,repository:$repo}) MERGE (test:Test {name:$name,repository:$repo}) SET test.file=$path MERGE (f)-[:CONTAINS]->(test) WITH test MATCH (prod:Class {name:$production,repository:$repo}) MERGE (prod)-[:TESTED_BY]->(test)",Map.of("path",t.file(),"repo",s.name(),"name",t.name(),"production",production));
                    }
                }
                if(s.commit()!=null) {
                    tx.run("MERGE (c:Commit {hash:$commit,repository:$repo}) SET c.timestamp=datetime()",Map.of("commit",s.commit(),"repo",s.name()));
                    var changedPaths=RepositorySyncService.git(Path.of(s.directory()),"diff-tree","--root","--no-commit-id","--name-only","-r",s.commit()).orElse("").lines().map(p->p.replace('\\','/')).filter(p->!p.isBlank()).toList();
                    if(!changedPaths.isEmpty()) tx.run("MATCH (c:Commit {hash:$commit,repository:$repo}),(f:File {repository:$repo}) WHERE f.path IN $paths MERGE (c)-[:MODIFIES]->(f)",Map.of("commit",s.commit(),"repo",s.name(),"paths",changedPaths));
                }
                return null;
            });
        }
        int nodes=s.documents().size()+s.types().size()+s.types().stream().mapToInt(t->t.methods().size()).sum();
        log.info("Neo4j graph transaction committed: repository={} indexedNodes={}",s.name(),nodes);
        return nodes;
    }
    @Override public Optional<String> repositoryDirectory(String repository) {
        if (driver == null) return Optional.empty();
        try (var session = driver.session()) {
            var result = session.run("MATCH (r:Repository {name:$repo}) RETURN r.directory AS directory", Map.of("repo", repository));
            if (!result.hasNext()) return Optional.empty();
            var value = result.single().get("directory");
            return value.isNull() || value.asString().isBlank() ? Optional.empty() : Optional.of(value.asString());
        }
    }
    @Override public Optional<String> repositoryCommit(String repository) {
        if (driver == null) return Optional.empty();
        try (var session = driver.session()) {
            var result = session.run("MATCH (r:Repository {name:$repo}) RETURN r.commit AS commit", Map.of("repo", repository));
            if (!result.hasNext()) return Optional.empty();
            var value = result.single().get("commit");
            return value.isNull() || value.asString().isBlank() ? Optional.empty() : Optional.of(value.asString());
        }
    }
    @Override public Map<String,String> repositoryFileHashes(String repository) {
        if (driver == null) return Map.of();
        try (var session = driver.session()) {
            var hashes = new HashMap<String,String>();
            var rows = session.run("MATCH (f:File {repository:$repo}) RETURN f.path AS path,f.hash AS hash", Map.of("repo", repository)).list();
            for (var row : rows) hashes.put(row.get("path").asString(), row.get("hash").asString());
            return Map.copyOf(hashes);
        }
    }
    @Override public void updateRepositoryMetadata(CodeModels.RepositorySnapshot snapshot) {
        if (driver == null) return;
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (r:Repository {name:$repo}) SET r.directory=$dir,r.commit=$commit",
                        Map.of("repo", snapshot.name(), "dir", snapshot.directory(), "commit", Objects.toString(snapshot.commit(), "")));
                return null;
            });
        }
    }
    @Override public void storeIncident(String repo,String caseId,String error,String file,String className,String method,
                                        String rootCause,String evidence,String resolution,double confidence,String proposedPatch) {
        if(driver==null) return;
        try(var session=driver.session()) {
            session.executeWrite(tx->{
                tx.run("MATCH (r:Repository {name:$repo}) MERGE (c:RCA_CASE {caseId:$id}) SET c.repository=$repo,c.errorMessage=$error,c.affectedFile=$file,c.affectedClass=$class,c.affectedMethod=$method,c.rootCause=$rootCause,c.evidence=$evidence,c.fixRecommendation=$resolution,c.resolution=$resolution,c.proposedPatch=$patch,c.confidence=$confidence,c.createdAt=datetime() MERGE (r)-[:HAS_CASE]->(c)",Map.ofEntries(Map.entry("repo",repo),Map.entry("id",caseId),Map.entry("error",error),Map.entry("file",Objects.toString(file,"")),Map.entry("class",Objects.toString(className,"")),Map.entry("method",Objects.toString(method,"")),Map.entry("rootCause",rootCause),Map.entry("evidence",evidence),Map.entry("resolution",resolution),Map.entry("patch",Objects.toString(proposedPatch,"")),Map.entry("confidence",confidence)));
                String exception=error.contains(":")?error.substring(0,error.indexOf(':')).trim():error.trim();
                tx.run("MATCH (c:RCA_CASE {caseId:$id,repository:$repo}) MERGE (e:Exception {name:$exception,repository:$repo}) MERGE (c)-[:RELATED_TO]->(e)",Map.of("repo",repo,"id",caseId,"exception",exception));
                return null;
            });
        }
        log.info("Stored RCA_CASE in Neo4j: repository={} caseId={}",repo,caseId);
    }
    @Override public Optional<String> incidentPatch(String repository,String caseId) {
        if (driver == null) return Optional.empty();
        try (var session=driver.session()) {
            var result=session.run("MATCH (c:RCA_CASE {repository:$repo,caseId:$id}) RETURN c.proposedPatch AS patch",
                    Map.of("repo",repository,"id",caseId));
            if (!result.hasNext()) return Optional.empty();
            String patch=result.single().get("patch").asString("");
            return patch.isBlank()?Optional.empty():Optional.of(patch);
        }
    }
    @Override public List<String> context(String repo,String className,String method) {
        if(driver==null) return List.of("Neo4j is not configured; using source and semantic retrieval only.");
        if(className==null||method==null) return List.of();
        try(var session=driver.session()) {
            var result=session.run("MATCH (c {repository:$repo})-[:HAS_METHOD]->(m:Method) WHERE c.name=$class AND m.name=$method OPTIONAL MATCH (caller:Method)-[:CALLS]->(m) RETURN c.name AS class,m.signature AS method,m.file AS file,m.line AS line,collect(caller.signature) AS callers LIMIT 20",Map.of("repo",repo,"class",className,"method",method));
            var out=new ArrayList<String>(); result.list(r->r.get("class").asString()+"."+r.get("method").asString()+" at "+r.get("file").asString()+":"+r.get("line").asInt()+"; callers="+r.get("callers").toString()).forEach(out::add);
            log.debug("Neo4j RCA context retrieved: repository={} class={} method={} matches={}",repo,className,method,out.size());
            return out;
        }
    }
    @Override public MethodContext methodContext(String repo,String className,String method) {
        if(driver==null||className==null||method==null) return GraphStore.super.methodContext(repo,className,method);
        try(var session=driver.session()) {
            var result=session.run("MATCH (c {repository:$repo})-[:HAS_METHOD]->(m:Method) WHERE c.name=$class AND m.name=$method " +
                    "OPTIONAL MATCH (caller:Method)-[:CALLS]->(m) " +
                    "OPTIONAL MATCH (m)-[:CALLS]->(callee:Method) " +
                    "OPTIONAL MATCH (c)-[:DEPENDS_ON]->(dep) " +
                    "OPTIONAL MATCH (m)-[:THROWS]->(ex:Exception) " +
                    "OPTIONAL MATCH (c)-[:TESTED_BY]->(test:Test) " +
                    "OPTIONAL MATCH (commit:Commit)-[:MODIFIES]->(file:File {repository:$repo}) WHERE file.path=m.file " +
                    "RETURN c.name AS owner,m.file AS file,m.line AS line,collect(DISTINCT caller.signature) AS callers, " +
                    "collect(DISTINCT callee.signature) AS callees,collect(DISTINCT dep.name) AS dependencies, " +
                    "collect(DISTINCT ex.name) AS exceptions,collect(DISTINCT test.name) AS tests,collect(DISTINCT commit.hash) AS commits LIMIT 1",
                    Map.of("repo",repo,"class",className,"method",method));
            if(!result.hasNext()) return GraphStore.super.methodContext(repo,className,method);
            var r=result.single();
            return new MethodContext(r.get("owner").asString(),r.get("file").asString(),r.get("line").asInt(),
                    strings(r.get("callers")),strings(r.get("callees")),strings(r.get("dependencies")),strings(r.get("exceptions")),
                    strings(r.get("tests")),strings(r.get("commits")));
        }
    }
    private static List<String> strings(org.neo4j.driver.Value value) { return value.asList(v -> v.isNull() ? null : v.asString()).stream().filter(Objects::nonNull).toList(); }
    @Override public List<StoredIncident> incidents(String repo,List<String> caseIds,int limit) {
        if(driver==null||limit<=0) return List.of();
        try(var session=driver.session()) {
            String query=caseIds.isEmpty()
                    ? "MATCH (c:RCA_CASE {repository:$repo}) RETURN c.caseId AS id,c.rootCause AS cause,c.resolution AS resolution ORDER BY c.createdAt DESC LIMIT $limit"
                    : "MATCH (c:RCA_CASE {repository:$repo}) WHERE c.caseId IN $ids RETURN c.caseId AS id,c.rootCause AS cause,c.resolution AS resolution ORDER BY c.createdAt DESC LIMIT $limit";
            var params=new HashMap<String,Object>();params.put("repo",repo);params.put("limit",limit);if(!caseIds.isEmpty())params.put("ids",caseIds);
            return session.run(query,params).list(r->new StoredIncident(r.get("id").asString(),r.get("cause").asString(""),r.get("resolution").asString("")));
        }
    }
    @Override public void close(){if(driver!=null) driver.close();}
}
