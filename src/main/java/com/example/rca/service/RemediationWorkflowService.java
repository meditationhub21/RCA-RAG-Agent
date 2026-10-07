package com.example.rca.service;

import com.example.rca.model.ApiModels.RemediationRequest;
import com.example.rca.model.ApiModels.RemediationPublishRequest;
import com.example.rca.model.ApiModels.RemediationResponse;
import com.example.rca.model.CodeModels;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Human-triggered verification of the source-grounded patch proposed for an RCA case. */
@Service
public class RemediationWorkflowService {
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(30);
    private static final DateTimeFormatter BRANCH_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private final RepositorySyncService repositories;
    private final GraphStore graph;
    private final boolean enabled;
    private final boolean publishingEnabled;

    public RemediationWorkflowService(RepositorySyncService repositories, GraphStore graph,
            @Value("${rca.remediation.enabled:false}") boolean enabled,
            @Value("${rca.remediation.publishing-enabled:false}") boolean publishingEnabled) {
        this.repositories = repositories;
        this.graph = graph;
        this.enabled = enabled;
        this.publishingEnabled = publishingEnabled;
    }

    public RemediationResponse applyAndVerify(RemediationRequest request) {
        if (!enabled) throw new IllegalStateException("Remediation workflow is disabled; enable rca.remediation.enabled after reviewing the security implications");
        if (request == null || blank(request.repositoryName()) || blank(request.caseId()))
            throw new IllegalArgumentException("repositoryName and caseId from an RCA response are required");
        String unifiedDiff = graph.incidentPatch(request.repositoryName(), request.caseId())
                .orElseThrow(() -> new IllegalArgumentException("This RCA case has no safe, source-grounded patch proposal. Review its fixRecommendation and provide the missing evidence before requesting remediation."));
        unifiedDiff = LlmRcaSynthesizer.normalizeHunkCounts(unifiedDiff);
        if (unifiedDiff == null)
            throw new IllegalArgumentException("The stored RCA patch is malformed. Rerun /rca with the latest application build to generate a valid proposal.");

        CodeModels.RepositorySnapshot snapshot = repositories.latest(request.repositoryName())
                .orElseThrow(() -> new IllegalArgumentException("Repository snapshot is unavailable; synchronize or restore it first"));
        Path root = Path.of(snapshot.directory()).toAbsolutePath().normalize();
        requireGitRoot(root);
        requireCleanWorktree(root);
        List<String> verify = verificationCommand(root);

        String title = blank(request.changeTitle()) ? "rca-fix-" + request.caseId().substring(0, Math.min(8, request.caseId().length())) : request.changeTitle();
        String branch = "codex/rca-" + slug(title) + "-" + BRANCH_TIME.format(Instant.now());
        Path patchFile = null;
        try {
            patchFile = Files.createTempFile("rca-remediation-", ".patch");
            Files.writeString(patchFile, unifiedDiff, StandardCharsets.UTF_8);
            try {
                run(root, List.of("git", "apply", "--check", "--", patchFile.toString()), Duration.ofSeconds(30));
            } catch (IllegalStateException e) {
                throw new IllegalArgumentException("The stored LLM patch is malformed or does not match the current repository. No branch was created; rerun /rca after syncing the repository.", e);
            }
            run(root, List.of("git", "switch", "-c", branch), Duration.ofSeconds(30));
            run(root, List.of("git", "apply", "--", patchFile.toString()), Duration.ofSeconds(30));

            ProcessResult result;
            try {
                result = run(root, verify, BUILD_TIMEOUT);
            } catch (IllegalStateException e) {
                return new RemediationResponse(request.repositoryName(), "VERIFICATION_FAILED", branch, null, tail(e.getMessage()), null);
            }
            if (result.exitCode() != 0) return new RemediationResponse(request.repositoryName(), "VERIFICATION_FAILED", branch,
                    null, tail(result.output()), null);

            commitChanges(root, request.changeTitle());
            String artifact = findArtifact(root);
            String prUrl = null;
            return new RemediationResponse(request.repositoryName(), "VERIFIED", branch, artifact, tail(result.output()), prUrl);
        } catch (IOException e) {
            throw new IllegalStateException("Could not prepare or apply remediation patch on branch " + branch, e);
        } finally {
            if (patchFile != null) try { Files.deleteIfExists(patchFile); } catch (IOException ignored) {}
        }
    }

    public RemediationResponse publish(RemediationPublishRequest request) {
        if (!publishingEnabled) throw new IllegalStateException("Publishing is disabled; set rca.remediation.publishing-enabled=true to allow Git push and PR creation");
        if (request == null || blank(request.repositoryName()) || request.branch() == null ||
                !request.branch().matches("codex/rca-[a-z0-9-]+"))
            throw new IllegalArgumentException("repositoryName and a codex/rca-* branch are required");
        Path root = repositories.latest(request.repositoryName()).map(s -> Path.of(s.directory()).toAbsolutePath().normalize())
                .orElseThrow(() -> new IllegalArgumentException("Repository snapshot is unavailable; synchronize or restore it first"));
        requireGitRoot(root);
        requireCleanWorktree(root);
        String currentBranch = run(root, List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), Duration.ofSeconds(30)).output().strip();
        if (!request.branch().equals(currentBranch))
            throw new IllegalStateException("Check out and review branch " + request.branch() + " before publishing");
        String prUrl = publishBranch(root, request.repositoryName(), request.branch(), request.changeTitle(), request.pullRequestBody());
        return new RemediationResponse(request.repositoryName(), "PUBLISHED", request.branch(), findArtifact(root),
                "Branch pushed and pull request created.", prUrl);
    }

    private static String publishBranch(Path root, String repository, String branch, String title, String requestedBody) {
        run(root, List.of("git", "push", "--set-upstream", "origin", branch), Duration.ofMinutes(5));
        String safeTitle = blank(title) ? "RCA remediation for " + repository : title.strip();
        String body = blank(requestedBody)
                ? "RCA remediation for repository `" + repository + "` on branch `" + branch + "`. The branch was verified with the repository test and package command. Review the diff and CI results before merging."
                : requestedBody.strip();
        return run(root, List.of("gh", "pr", "create", "--title", safeTitle, "--body", body), Duration.ofMinutes(2)).output().strip();
    }

    private static void commitChanges(Path root, String title) {
        String tracked = run(root, List.of("git", "diff", "--name-only"), Duration.ofSeconds(30)).output();
        String untracked = run(root, List.of("git", "ls-files", "--others", "--exclude-standard"), Duration.ofSeconds(30)).output();
        var files = java.util.stream.Stream.concat(tracked.lines(), untracked.lines()).map(String::strip)
                .filter(s -> !s.isBlank()).distinct().toList();
        if (files.isEmpty()) throw new IllegalStateException("The remediation patch produced no changes to commit");
        var add = new ArrayList<String>(List.of("git", "add", "--"));
        add.addAll(files);
        run(root, add, Duration.ofSeconds(30));
        String message = blank(title) ? "fix: apply reviewed RCA remediation" : "fix: " + title.strip();
        run(root, List.of("git", "commit", "-m", message), Duration.ofSeconds(60));
    }

    private static void requireGitRoot(Path root) {
        if (!Files.isDirectory(root) || !Files.exists(root.resolve(".git")))
            throw new IllegalArgumentException("The synchronized repository path must be a Git working tree");
        String top = run(root, List.of("git", "rev-parse", "--show-toplevel"), Duration.ofSeconds(30)).output().strip();
        if (!Path.of(top).toAbsolutePath().normalize().equals(root))
            throw new IllegalArgumentException("The synchronized path is not the Git repository root");
    }

    private static void requireCleanWorktree(Path root) {
        String status = run(root, List.of("git", "status", "--porcelain"), Duration.ofSeconds(30)).output().strip();
        if (!status.isEmpty()) throw new IllegalStateException("Repository has uncommitted changes; commit or stash them before applying remediation");
    }

    private static List<String> verificationCommand(Path root) {
        if (Files.exists(root.resolve("mvnw.cmd"))) return List.of("cmd", "/c", "mvnw.cmd", "-B", "verify");
        if (Files.exists(root.resolve("mvnw"))) return List.of("./mvnw", "-B", "verify");
        if (Files.exists(root.resolve("gradlew.bat"))) return List.of("cmd", "/c", "gradlew.bat", "test", "bootJar");
        if (Files.exists(root.resolve("gradlew"))) return List.of("./gradlew", "test", "bootJar");
        if (Files.exists(root.resolve("pom.xml"))) return List.of("mvn", "-B", "verify");
        if (Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts")))
            return List.of("gradle", "test", "bootJar");
        throw new IllegalArgumentException("No Maven or Gradle build file was found; verification is not supported for this repository");
    }

    private static String findArtifact(Path root) {
        Path target = root.resolve("target");
        Path build = root.resolve("build/libs");
        var candidates = new ArrayList<Path>();
        for (Path directory : List.of(target, build)) {
            if (!Files.isDirectory(directory)) continue;
            try (var files = Files.walk(directory)) {
                candidates.addAll(files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".jar")).toList());
            } catch (IOException ignored) {}
        }
        return candidates.stream().max(java.util.Comparator.comparingLong(p -> p.toFile().lastModified())).map(Path::toString).orElse(null);
    }

    private static ProcessResult run(Path directory, List<String> command, Duration timeout) {
        Path outputFile = null;
        try {
            outputFile = Files.createTempFile("rca-workflow-", ".log");
            Process process = new ProcessBuilder(new ArrayList<>(command)).directory(directory.toFile())
                    .redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Command timed out after " + timeout.toMinutes() + " minutes: " + command.getFirst());
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IllegalStateException("Command failed (" + process.exitValue() + "): " +
                    command.getFirst() + "\n" + tail(output));
            return new ProcessResult(process.exitValue(), output);
        } catch (IOException e) {
            throw new IllegalStateException("Could not run " + command.getFirst() + "; verify that the required tool is installed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + command.getFirst(), e);
        } finally {
            if (outputFile != null) try { Files.deleteIfExists(outputFile); } catch (IOException ignored) {}
        }
    }

    private static String tail(String output) {
        if (output == null) return "";
        String[] lines = output.lines().toArray(String[]::new);
        int start = Math.max(0, lines.length - 80);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, start, lines.length));
    }
    private static String slug(String value) {
        String slug = Objects.toString(value, "fix").toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return slug.isBlank() ? "fix" : slug.substring(0, Math.min(32, slug.length()));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private record ProcessResult(int exitCode, String output) {}
}
