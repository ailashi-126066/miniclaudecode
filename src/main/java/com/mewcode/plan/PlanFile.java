// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.plan;

import java.io.IOException;
import java.nio.file.*;

/**
 * Manages plan files stored under {@code .mewcode/plans/} in the working
 * directory.
 * <p>
 * Plan Mode and structured plans share the same {@code active.md} file.
 */
public class PlanFile {

    private static final String PLANS_DIR = ".mewcode/plans";

    private static String currentPlanPath;

    // ── Slug generation ─────────────────────────────────────────────────

    // ── Path management ─────────────────────────────────────────────────

    public static String getOrCreatePlanPath(String workDir) {
        Path dir = Path.of(workDir, PLANS_DIR);
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // best effort
        }
        currentPlanPath = dir.resolve("active.md").toString();
        return currentPlanPath;
    }

    public static String getPlanFilePath(String workDir) {
        if (currentPlanPath != null) {
            return currentPlanPath;
        }
        return getOrCreatePlanPath(workDir);
    }

    public static void setPlanFilePath(String path) {
        currentPlanPath = path;
    }

    public static void resetPlanPath() {
        currentPlanPath = null;
    }

    // ── Persistence ─────────────────────────────────────────────────────

    public static boolean planExists() {
        return currentPlanPath != null && Files.exists(Path.of(currentPlanPath));
    }

    public static String loadPlan() throws IOException {
        if (currentPlanPath == null) {
            return "";
        }
        Path path = Path.of(currentPlanPath);
        if (!Files.exists(path)) {
            return "";
        }
        return Files.readString(path);
    }

    public static void savePlan(String workDir, String content) throws IOException {
        String path = getOrCreatePlanPath(workDir);
        Path target = Path.of(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    // ── Utilities ───────────────────────────────────────────────────────

    /**
     * Returns {@code true} when {@code targetPath} refers to the same file
     * as {@code planPath} (after normalization) or when one is a suffix of
     * the other. This matches the Go helper
     * {@code IsPlanFilePath(targetPath, planPath)}.
     */
    public static boolean isPlanFilePath(String targetPath, String planPath) {
        if (planPath == null || planPath.isBlank()) {
            return false;
        }
        String cleanTarget = Path.of(targetPath).normalize().toString();
        String cleanPlan = Path.of(planPath).normalize().toString();
        return cleanTarget.equals(cleanPlan) || cleanTarget.endsWith(cleanPlan);
    }
}
