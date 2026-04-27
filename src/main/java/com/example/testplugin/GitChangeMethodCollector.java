package com.example.testplugin;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import git4idea.GitCommit;
import git4idea.history.GitHistoryUtils;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GitChangeMethodCollector {

    private GitChangeMethodCollector() {
    }

    // ── 加载最近 commit 列表 ─────────────────────────────────────────────────

    public static @NotNull List<CommitEntry> loadRecentCommits(@NotNull Project project, int maxCount) {
        GitRepositoryManager repoManager = GitRepositoryManager.getInstance(project);
        List<GitRepository> repos = repoManager.getRepositories();
        if (repos.isEmpty()) {
            return List.of();
        }

        List<CommitEntry> result = new ArrayList<>();
        for (GitRepository repo : repos) {
            try {
                List<GitCommit> commits = GitHistoryUtils.history(
                        project, repo.getRoot(), "--max-count=" + maxCount);
                for (GitCommit commit : commits) {
                    result.add(new CommitEntry(commit));
                }
            } catch (VcsException ignored) {
                // 跳过读取失败的仓库
            }
        }
        return result;
    }

    // ── 分析某次 commit 的变更类和方法 ──────────────────────────────────────

    public static @NotNull String analyzeCommit(@NotNull Project project, @NotNull GitCommit commit) {
        Collection<Change> changes = commit.getChanges();

        List<Change> javaChanges = new ArrayList<>();
        for (Change change : changes) {
            if (isJavaChange(change)) {
                javaChanges.add(change);
            }
        }

        if (javaChanges.isEmpty()) {
            return "本次提交中没有 Java 文件变更。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Commit: ").append(commit.getId().toShortString())
                .append("  ").append(commit.getSubject()).append("\n");
        builder.append("变更 Java 文件数: ").append(javaChanges.size()).append("\n\n");

        for (Change change : javaChanges) {
            appendChangeAnalysis(project, change, builder);
        }

        return builder.toString();
    }

    // ── 单个变更文件的分析与输出 ─────────────────────────────────────────────

    private static void appendChangeAnalysis(@NotNull Project project,
                                             @NotNull Change change,
                                             @NotNull StringBuilder builder) {
        ContentRevision afterRevision = change.getAfterRevision();
        ContentRevision beforeRevision = change.getBeforeRevision();

        String displayPath = afterRevision != null
                ? afterRevision.getFile().getPath()
                : beforeRevision.getFile().getPath();

        String fileName = displayPath.substring(displayPath.lastIndexOf('/') + 1);

        String typeLabel = switch (change.getType()) {
            case NEW -> "[新增]";
            case DELETED -> "[删除]";
            case MOVED -> "[移动/重命名]";
            default -> "[修改]";
        };

        builder.append(typeLabel).append(" ").append(displayPath).append("\n");

        if (change.getType() == Change.Type.DELETED) {
            Map<String, List<String>> methodsBefore = getMethodsFromRevision(project, beforeRevision, fileName);
            appendMethodsWithMarker(methodsBefore, "- ", builder);
        } else if (change.getType() == Change.Type.NEW) {
            Map<String, List<String>> methodsAfter = getMethodsFromRevision(project, afterRevision, fileName);
            appendMethodsWithMarker(methodsAfter, "+ ", builder);
        } else {
            // MODIFIED / MOVED：对比前后版本，标出新增、删除、保留的方法
            Map<String, List<String>> methodsBefore = getMethodsFromRevision(project, beforeRevision, fileName);
            Map<String, List<String>> methodsAfter = getMethodsFromRevision(project, afterRevision, fileName);
            appendMethodDiff(methodsBefore, methodsAfter, builder);
        }

        builder.append("\n");
    }

    // ── 方法差异展示（+新增  -删除  =未变） ──────────────────────────────────

    private static void appendMethodDiff(@NotNull Map<String, List<String>> before,
                                         @NotNull Map<String, List<String>> after,
                                         @NotNull StringBuilder builder) {
        Set<String> allClasses = new LinkedHashSet<>();
        allClasses.addAll(before.keySet());
        allClasses.addAll(after.keySet());

        for (String className : allClasses) {
            Set<String> beforeSet = new LinkedHashSet<>(before.getOrDefault(className, List.of()));
            Set<String> afterSet = new LinkedHashSet<>(after.getOrDefault(className, List.of()));

            Set<String> added = new LinkedHashSet<>(afterSet);
            added.removeAll(beforeSet);

            Set<String> removed = new LinkedHashSet<>(beforeSet);
            removed.removeAll(afterSet);

            Set<String> unchanged = new LinkedHashSet<>(afterSet);
            unchanged.retainAll(new HashSet<>(beforeSet));

            builder.append("  ").append(className).append("\n");
            for (String m : added) {
                builder.append("    + ").append(m).append("\n");
            }
            for (String m : removed) {
                builder.append("    - ").append(m).append("\n");
            }
            for (String m : unchanged) {
                builder.append("    = ").append(m).append("\n");
            }
        }
    }

    private static void appendMethodsWithMarker(@NotNull Map<String, List<String>> classMethods,
                                                @NotNull String marker,
                                                @NotNull StringBuilder builder) {
        for (Map.Entry<String, List<String>> entry : classMethods.entrySet()) {
            builder.append("  ").append(entry.getKey()).append("\n");
            for (String method : entry.getValue()) {
                builder.append("    ").append(marker).append(method).append("\n");
            }
        }
    }

    // ── 通过 ContentRevision 获取该版本下的所有类方法 ────────────────────────

    private static @NotNull Map<String, List<String>> getMethodsFromRevision(
            @NotNull Project project,
            @Nullable ContentRevision revision,
            @NotNull String fileName) {
        if (revision == null) {
            return Map.of();
        }
        try {
            String content = revision.getContent();
            if (content == null) {
                return Map.of();
            }
            return ReadAction.compute(() -> extractMethodsFromContent(project, fileName, content));
        } catch (VcsException ignored) {
            return Map.of();
        }
    }

    private static @NotNull Map<String, List<String>> extractMethodsFromContent(
            @NotNull Project project,
            @NotNull String fileName,
            @NotNull String content) {
        PsiFile psiFile = PsiFileFactory.getInstance(project)
                .createFileFromText(fileName, JavaFileType.INSTANCE, content);

        if (!(psiFile instanceof PsiJavaFile javaFile)) {
            return Map.of();
        }

        Map<String, List<String>> classMethods = new LinkedHashMap<>();
        javaFile.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass aClass) {
                super.visitClass(aClass);
                List<String> methods = new ArrayList<>();
                for (PsiMethod method : aClass.getMethods()) {
                    if (!method.isConstructor()) {
                        methods.add(buildMethodSignature(method));
                    }
                }
                classMethods.put(resolveClassName(aClass), methods);
            }
        });
        return classMethods;
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────────

    private static boolean isJavaChange(@NotNull Change change) {
        ContentRevision rev = change.getAfterRevision() != null
                ? change.getAfterRevision()
                : change.getBeforeRevision();
        return rev != null && rev.getFile().getPath().endsWith(".java");
    }

    private static @NotNull String resolveClassName(@NotNull PsiClass psiClass) {
        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName != null && !qualifiedName.isBlank()) {
            return qualifiedName;
        }
        return psiClass.getName() == null ? "<anonymous class>" : psiClass.getName();
    }

    private static @NotNull String buildMethodSignature(@NotNull PsiMethod method) {
        StringBuilder builder = new StringBuilder();
        builder.append(method.getName()).append("(");
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            PsiType type = parameters[i].getType();
            builder.append(type.getPresentableText());
        }
        builder.append(")");
        return builder.toString();
    }
}

