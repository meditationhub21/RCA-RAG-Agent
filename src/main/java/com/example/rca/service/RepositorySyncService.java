package com.example.rca.service;

import com.example.rca.model.ApiModels.SyncResponse;
import com.example.rca.model.CodeModels;
import com.example.rca.parser.JavaAstParser;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RepositorySyncService {
    private static final Logger log=LoggerFactory.getLogger(RepositorySyncService.class);
    private final JavaAstParser parser; private final GraphStore graph; private final VectorStore vectors; private final ObjectMapper mapper; private final BuildDependencyScanner dependencyScanner;
    private final String allowedRoot; private final String indexDirectory;
    private final Map<String,CodeModels.RepositorySnapshot> snapshots=new ConcurrentHashMap<>();
    @Autowired
    public RepositorySyncService(JavaAstParser parser,GraphStore graph,VectorStore vectors,ObjectMapper mapper,BuildDependencyScanner dependencyScanner,
            @Value("${rca.repositories.allowed-root:}") String allowedRoot,@Value("${rca.index-directory:.rca-index-gemma4-nomic-v1.5}") String indexDirectory) {
        this.parser=parser;this.graph=graph;this.vectors=vectors;this.mapper=mapper;this.dependencyScanner=dependencyScanner;this.allowedRoot=allowedRoot;this.indexDirectory=indexDirectory;
    }
    public RepositorySyncService(JavaAstParser parser,GraphStore graph,VectorStore vectors,ObjectMapper mapper,String allowedRoot,String indexDirectory) {
        this(parser,graph,vectors,mapper,new BuildDependencyScanner(),allowedRoot,indexDirectory);
    }
    public SyncResponse sync(String name,String sourceDirectory) {
        if(name==null||name.isBlank()) throw new IllegalArgumentException("repositoryName is required");
        long syncStarted = System.nanoTime();
        try {
            log.info("Repository sync started: repository={} sourceDirectory={}",name,sourceDirectory);
            Path root=Path.of(Objects.requireNonNull(sourceDirectory,"sourceDirectory is required")).toRealPath();
            if(!Files.isDirectory(root)) throw new IllegalArgumentException("sourceDirectory must be a directory");
            if(allowedRoot!=null&&!allowedRoot.isBlank()&&!root.startsWith(Path.of(allowedRoot).toRealPath())) throw new IllegalArgumentException("sourceDirectory is outside REPOSITORY_ALLOWED_ROOT");
            Path manifest=manifestPath(name);
            log.debug("Using repository hash manifest: repository={} manifest={}",name,manifest);
            Map<String,String> priorIndex=readIndex(manifest);
            if (priorIndex.isEmpty() && graph.repositoryDirectory(name).isPresent()) {
                priorIndex = new TreeMap<>(graph.repositoryFileHashes(name));
                if (!priorIndex.isEmpty()) log.info("Loaded prior file hashes from persistent graph metadata: repository={} files={}", name, priorIndex.size());
            }
            final Map<String,String> prior = priorIndex;
            long scanStarted = System.nanoTime();
            ScannedRepository scanned = scan(root, name);
            var docs=scanned.documents(); var types=scanned.types(); var hashes=scanned.hashes();
            log.info("Repository scan complete: repository={} filesScanned={} javaTypes={} elapsedMs={}",name,docs.size(),types.size(),
                    (System.nanoTime() - scanStarted) / 1_000_000);
            int changed=(int)hashes.entrySet().stream().filter(e->!Objects.equals(prior.get(e.getKey()),e.getValue())).count()+ (int)prior.keySet().stream().filter(k->!hashes.containsKey(k)).count();
            String commit=git(root,"rev-parse","HEAD").orElse(null);
            if(commit==null) log.warn("No Git HEAD found for repository {}; commit and Git change metadata will be unavailable",name);
            else log.info("Repository Git HEAD found: repository={} commit={}",name,commit);
            var dependencies=dependencyScanner.scan(docs);
            log.info("Build dependency inventory created: repository={} declaredDependencies={}",name,dependencies.size());
            var snapshot=new CodeModels.RepositorySnapshot(name,root.toString(),commit,docs,types,dependencies);
            snapshots.put(name,snapshot);
            long graphStarted = System.nanoTime();
            boolean graphExists = graph.repositoryDirectory(name).isPresent();
            boolean graphCommitUnchanged = Objects.equals(graph.repositoryCommit(name).orElse(null), commit);
            int graphNodes;
            if (changed == 0 && graphExists) {
                if (!graphCommitUnchanged) graph.updateRepositoryMetadata(snapshot);
                graphNodes = 0;
                log.info("Repository graph unchanged; skipped entity upsert: repository={} commitChanged={}", name, !graphCommitUnchanged);
            } else {
                graphNodes=graph.upsert(snapshot);
            }
            log.info("Repository graph write timing: repository={} nodes={} elapsedMs={}", name, graphNodes,
                    (System.nanoTime() - graphStarted) / 1_000_000);
            var vectorEntries=new ArrayList<VectorStore.Entry>();
            var persistedVectorIds = vectors.idsForNamespace(name);
            for(var e:prior.entrySet()) if(!Objects.equals(hashes.get(e.getKey()),e.getValue())) vectors.deletePrefix(name+":"+e.getKey());
            for(var d:docs) {
                String id=name+":"+d.path();
                if(!Objects.equals(prior.get(d.path()),d.sha256()) || !persistedVectorIds.contains(id))
                    vectorEntries.add(new VectorStore.Entry(id,d.path()+"\n"+d.content()));
            }
            for(var t:types) for(var m:t.methods()) {
                String id=name+":"+t.file()+"#"+t.name()+"."+m.name();
                if(!Objects.equals(prior.get(t.file()),hashes.get(t.file())) || !persistedVectorIds.contains(id))
                    vectorEntries.add(new VectorStore.Entry(id,"Repository "+name+" class "+t.name()+" method "+m.name()+"\n"+m.body()));
            }
            log.info("Repository vector indexing started: repository={} vectorEntries={} persistedVectorIds={} changedFiles={}",name,vectorEntries.size(),persistedVectorIds.size(),changed);
            long vectorStarted = System.nanoTime();
            vectors.upsertAll(vectorEntries);
            log.info("Repository vector indexing complete: repository={} vectorEntries={} elapsedMs={}", name,
                    vectorEntries.size(), (System.nanoTime() - vectorStarted) / 1_000_000);
            int embeddings=vectorEntries.size();
            Files.createDirectories(manifest.getParent());
            mapper.writeValue(manifest.toFile(),hashes);
            var response=new SyncResponse(name,"SYNCED",commit,docs.size(),changed,graphNodes,embeddings,
                    dependencies.stream().map(CodeModels.DependencyInfo::coordinate).distinct().toList());
            log.info("Repository sync completed: repository={} status={} filesChanged={} graphNodesUpdated={} vectorEntriesUpdated={} commit={}",
                    name,response.status(),response.filesChanged(),response.graphNodesUpdated(),response.embeddingsUpdated(),commit);
            log.info("Repository sync total time: repository={} elapsedMs={}", name, (System.nanoTime() - syncStarted) / 1_000_000);
            return response;
        } catch(IllegalArgumentException e){log.warn("Repository sync rejected: repository={} reason={}",name,e.getMessage());throw e;}
        catch(Exception e){log.error("Repository sync failed: repository={} sourceDirectory={}",name,sourceDirectory,e);throw new IllegalStateException("Repository sync failed: "+e.getMessage(),e);}
    }
    public Optional<CodeModels.RepositorySnapshot> latest(String name) {
        CodeModels.RepositorySnapshot cached = snapshots.get(name);
        if (cached != null) return Optional.of(cached);
        Optional<String> persistedDirectory = graph.repositoryDirectory(name);
        if (persistedDirectory.isEmpty()) return Optional.empty();
        try {
            Path root = Path.of(persistedDirectory.get()).toRealPath();
            if (!Files.isDirectory(root)) return Optional.empty();
            if (allowedRoot != null && !allowedRoot.isBlank() && !root.startsWith(Path.of(allowedRoot).toRealPath())) {
                log.warn("Stored repository path is outside REPOSITORY_ALLOWED_ROOT: repository={}", name);
                return Optional.empty();
            }
            long started = System.nanoTime();
            ScannedRepository scanned = scan(root, name);
            String commit = git(root, "rev-parse", "HEAD").orElse(graph.repositoryCommit(name).orElse(null));
            var snapshot = new CodeModels.RepositorySnapshot(name, root.toString(), commit, scanned.documents(), scanned.types(),
                    dependencyScanner.scan(scanned.documents()));
            snapshots.put(name, snapshot);
            log.info("Repository snapshot restored from persisted graph metadata: repository={} files={} types={} elapsedMs={}",
                    name, scanned.documents().size(), scanned.types().size(), (System.nanoTime() - started) / 1_000_000);
            return Optional.of(snapshot);
        } catch (Exception e) {
            log.warn("Could not restore repository snapshot from persisted graph metadata: repository={} reason={}", name, e.getMessage());
            return Optional.empty();
        }
    }
    private ScannedRepository scan(Path root, String name) throws IOException {
        var docs=new ArrayList<CodeModels.SourceDocument>();
        var types=new ArrayList<CodeModels.TypeInfo>();
        var hashes=new TreeMap<String,String>();
        try(Stream<Path> paths=Files.walk(root)) {
            paths.filter(Files::isRegularFile).filter(p->!excluded(root.relativize(p))).filter(RepositorySyncService::indexable).forEach(p->{
                try {
                    String rel=root.relativize(p).toString().replace('\\','/'); String content=Files.readString(p); String hash=sha256(content); hashes.put(rel,hash);
                    String kind=p.toString().endsWith(".java")?"JAVA":"CONFIG"; docs.add(new CodeModels.SourceDocument(rel,content,hash,kind));
                    if(kind.equals("JAVA")) types.addAll(parser.parse(p,rel,content));
                } catch(IOException e){throw new UncheckedIOException(e);}
            });
        }
        return new ScannedRepository(List.copyOf(docs), List.copyOf(types), hashes);
    }
    private Path manifestPath(String name) {
        Path directory=Path.of(indexDirectory);
        if(!directory.isAbsolute()) directory=Path.of(System.getProperty("user.dir")).resolve(directory);
        return directory.resolve(name.replaceAll("[^A-Za-z0-9._-]","_")+".json");
    }
    private record ScannedRepository(List<CodeModels.SourceDocument> documents, List<CodeModels.TypeInfo> types, Map<String,String> hashes) {}
    private Map<String,String> readIndex(Path path){try { if(Files.exists(path)) return mapper.readValue(path.toFile(),mapper.getTypeFactory().constructMapType(TreeMap.class,String.class,String.class)); }catch(Exception ignored){} return Map.of();}
    private static boolean indexable(Path p){String s=p.toString().replace('\\','/');return s.endsWith(".java")||s.endsWith(".yml")||s.endsWith(".yaml")||s.endsWith(".properties")||s.endsWith(".toml")||s.endsWith("pom.xml")||s.endsWith("build.gradle")||s.endsWith("build.gradle.kts");}
    private static boolean excluded(Path p){for(Path part:p) if(Set.of(".git","target","build","node_modules",".idea").contains(part.toString())) return true;return false;}
    private static String sha256(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static Optional<String> git(Path root,String... args){try{var cmd=new ArrayList<String>();cmd.add("git");cmd.add("-C");cmd.add(root.toString());cmd.addAll(List.of(args));var p=new ProcessBuilder(cmd).redirectErrorStream(true).start();String s=new String(p.getInputStream().readAllBytes()).trim();return p.waitFor()==0&&!s.isBlank()?Optional.of(s):Optional.empty();}catch(Exception e){return Optional.empty();}}
}
