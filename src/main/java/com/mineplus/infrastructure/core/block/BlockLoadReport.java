package com.mineplus.infrastructure.core.block;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** Outcome of one {@code blocks/} load: what was accepted and what was not. */
public final class BlockLoadReport {

    public enum Severity {
        ERROR,
        WARN
    }

    public record Problem(File file, Severity severity, String message) {
    }

    private final List<Problem> problems;
    private final int accepted;

    public BlockLoadReport(List<Problem> problems, int accepted) {
        this.problems = Collections.unmodifiableList(problems);
        this.accepted = accepted;
    }

    public List<Problem> problems() {
        return problems;
    }

    public int accepted() {
        return accepted;
    }

    public boolean hasErrors() {
        return problems.stream().anyMatch(p -> p.severity() == Severity.ERROR);
    }

    public List<Problem> errors() {
        return problems.stream()
                .filter(p -> p.severity() == Severity.ERROR)
                .collect(Collectors.toList());
    }

    public List<Problem> warnings() {
        return problems.stream()
                .filter(p -> p.severity() == Severity.WARN)
                .collect(Collectors.toList());
    }

    public String summary() {
        return accepted + " accepted, " + errors().size() + " error(s), "
                + warnings().size() + " warning(s)";
    }
}
