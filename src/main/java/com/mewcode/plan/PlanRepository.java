package com.mewcode.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Stores one active plan in one Markdown file. The visible Markdown is for
 * people and the model; the trailing HTML comment carries the exact
 * {@link PlanState} needed by the plan tools.
 */
public final class PlanRepository {
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final String STATE_OPEN = "<!-- mewcode-plan-state\n";
    private static final String STATE_CLOSE = "\n-->";

    private final Path markdownPath;
    private final Path legacyJsonPath;

    public PlanRepository(Path workspace) {
        markdownPath = workspace.resolve(".mewcode/plans/active.md");
        legacyJsonPath = workspace.resolve(".mewcode/plans/active.json");
    }

    public synchronized void save(PlanState plan) {
        try {
            Files.createDirectories(markdownPath.getParent());
            String state = JSON.writeValueAsString(plan);
            Files.writeString(markdownPath, render(plan) + "\n\n" + STATE_OPEN + state + STATE_CLOSE + "\n");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot save plan", e);
        }
    }

    public synchronized Optional<PlanState> load() {
        if (!Files.isRegularFile(markdownPath)) return migrateLegacyJson();
        try {
            String text = Files.readString(markdownPath);
            int start = text.indexOf(STATE_OPEN);
            int end = start < 0 ? -1 : text.indexOf(STATE_CLOSE, start + STATE_OPEN.length());
            if (start < 0 || end < 0) return Optional.empty();
            String state = text.substring(start + STATE_OPEN.length(), end).trim();
            return Optional.of(JSON.readValue(state, PlanState.class));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /** Converts the former active.json once, then leaves active.md as the only Plan source. */
    private Optional<PlanState> migrateLegacyJson() {
        if (!Files.isRegularFile(legacyJsonPath)) return Optional.empty();
        try {
            PlanState legacy = JSON.readValue(legacyJsonPath.toFile(), PlanState.class);
            save(legacy);
            Files.deleteIfExists(legacyJsonPath);
            return Optional.of(legacy);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /** Returns the readable Plan Markdown without the internal state comment. */
    public synchronized String readForModel() {
        if (!Files.isRegularFile(markdownPath)) return "";
        try {
            String text = Files.readString(markdownPath);
            int stateStart = text.indexOf(STATE_OPEN);
            return (stateStart < 0 ? text : text.substring(0, stateStart)).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    public Path path() {
        return markdownPath;
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(markdownPath);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String render(PlanState plan) {
        StringBuilder text = new StringBuilder("# ").append(plan.goal())
                .append("\n\nStatus: ").append(plan.status()).append("\n");
        for (PlanState.Step step : plan.steps()) {
            text.append("\n## ").append(step.id()).append(": ").append(step.description()).append("\n")
                    .append("状态：").append(step.status()).append("\n");
            if (!step.dependsOn().isEmpty()) text.append("依赖：").append(String.join(", ", step.dependsOn())).append("\n");
            text.append("需要验证：").append(step.requiresVerification()).append("\n");
            if (!step.acceptanceCriteria().isEmpty()) {
                text.append("验收条件：\n");
                step.acceptanceCriteria().forEach(item -> text.append("- ").append(item).append("\n"));
            }
            if (step.evidence() != null) {
                if (!step.evidence().verificationResults().isEmpty()) {
                    text.append("验证结果：\n");
                    step.evidence().verificationResults().forEach(item -> text.append("- ").append(item).append("\n"));
                }
                if (!step.evidence().changedFiles().isEmpty()) {
                    text.append("修改文件：\n");
                    step.evidence().changedFiles().forEach(item -> text.append("- ").append(item).append("\n"));
                }
                if (!step.evidence().failureReason().isBlank()) {
                    text.append("失败原因：").append(step.evidence().failureReason()).append("\n");
                }
            }
        }
        return text.toString().trim();
    }
}
