package com.example.rca.service;

import com.example.rca.model.CodeModels;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class GitAnalysisService {
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-fA-F]{7,40}\\b.*");

    public GitContext inspect(CodeModels.RepositorySnapshot snapshot, String file, Integer line) {
        if (snapshot == null || snapshot.directory() == null || file == null || line == null) {
            return new GitContext(List.of(), List.of(), null);
        }
        Path root = Path.of(snapshot.directory());
        String blame = RepositorySyncService.git(root, "blame", "-L", line + "," + line, "--", file).orElse(null);
        String introducing = blame == null ? null : blame.lines().filter(s -> COMMIT.matcher(s.trim()).matches())
                .map(s -> s.trim().substring(0, s.trim().indexOf(' ')).replaceAll("^[^(]*\\(", ""))
                .findFirst().orElse(null);
        var history = RepositorySyncService.git(root, "log", "-5", "--format=%h %ad %s", "--date=short", "--", file)
                .map(s -> s.lines().limit(5).toList()).orElse(List.of());
        var diffs = new ArrayList<String>();
        if (introducing != null) {
            RepositorySyncService.git(root, "show", "--format=Commit %h%nSubject: %s", "--no-ext-diff", "--unified=3", introducing, "--", file)
                    .ifPresent(s -> diffs.add(s.substring(0, Math.min(s.length(), 6000))));
        }
        return new GitContext(history, diffs, introducing);
    }

    public record GitContext(List<String> history, List<String> diffs, String likelyIntroducingCommit) {
        public GitContext { history = List.copyOf(history); diffs = List.copyOf(diffs); }
    }
}
