package com.example.testplugin;

import git4idea.GitCommit;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.Date;

public record CommitEntry(@NotNull GitCommit commit) {

    @Override
    public String toString() {
        String hash = commit.getId().toShortString();
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date(commit.getCommitTime()));
        String author = commit.getAuthor().getName();
        String subject = commit.getSubject();
        return String.format("[%s]  %s  %-20s  %s", hash, date, author, subject);
    }
}
