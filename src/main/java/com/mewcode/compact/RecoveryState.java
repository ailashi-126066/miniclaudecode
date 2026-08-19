// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.compact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-agent file-read metadata that needs to survive Layer 2 compaction.
 *
 * <p>Compact wipes the working transcript; without these records the
 * model would forget which files it had just read and which skill SOPs it
 * was operating under. {@link ContextCompactor#buildRecoveryAttachment}
 * renders the recorded data into a single attachment block that gets
 * appended to the post-compact summary message.
 *
 * <p>Thread-safe: tool callbacks may fire from multiple virtual threads in
 * the streaming executor.
 */
public final class RecoveryState {

    /** Path and time of a successful file read. File content is not retained. */
    public record FileReadRecord(String path, Instant timestamp) {}

    /** Snapshot of the SOP body delivered to the model when a skill ran. */
    public record SkillInvocationRecord(String name, String body, Instant timestamp) {}

    /** Current structured plan captured immediately before compaction. */
    public record PlanRecord(String path, String content, Instant timestamp) {}

    /** A command that checked, tested, built, or linted the workspace. */
    public record ValidationRecord(String command, boolean passed, String output, Instant timestamp) {}

    /** A user decision made for a permission request. */
    public record ApprovalRecord(String toolName, String decision, String description, Instant timestamp) {}

    private final Object lock = new Object();
    private final Map<String, FileReadRecord> files = new HashMap<>();

    private final Map<String, SkillInvocationRecord> skills = new HashMap<>();
    private PlanRecord plan;
    private final List<ValidationRecord> validations = new ArrayList<>();
    private final List<ApprovalRecord> approvals = new ArrayList<>();

    /** Overwrites any prior record for the same path so the latest read wins. */
    public void recordFileRead(String path) {
        if (path == null || path.isEmpty()) return;

        synchronized (lock) {
            files.put(path, new FileReadRecord(path, Instant.now()));
        }
    }

    /** Overwrites any prior record for the same skill name. */
    public void recordSkillInvocation(String name, String body) {
        if (name == null || name.isEmpty()) return;
        synchronized (lock) {
            skills.put(name, new SkillInvocationRecord(name, body, Instant.now()));
        }
    }

    /** Replaces the prior active-plan snapshot. */
    public void recordPlan(String path, String content) {
        if (path == null || path.isBlank() || content == null || content.isBlank()) return;
        synchronized (lock) {
            plan = new PlanRecord(path, content, Instant.now());
        }
    }

    /** Clears the plan snapshot after the active plan file has disappeared. */
    public void clearPlan() {
        synchronized (lock) {
            plan = null;
        }
    }

    /** Keeps the most recent verification commands and their outcomes. */
    public void recordValidation(String command, boolean passed, String output) {
        if (command == null || command.isBlank()) return;
        synchronized (lock) {
            validations.add(new ValidationRecord(command, passed, output == null ? "" : output, Instant.now()));
            if (validations.size() > 5) validations.removeFirst();
        }
    }

    /** Keeps the most recent user permission decisions for recovery after compacting. */
    public void recordApproval(String toolName, String decision, String description) {
        if (toolName == null || toolName.isBlank() || decision == null || decision.isBlank()) return;
        synchronized (lock) {
            approvals.add(new ApprovalRecord(toolName, decision, description == null ? "" : description, Instant.now()));
            if (approvals.size() > 10) approvals.removeFirst();
        }
    }

    /** Returns up to {@code limit} file records, newest first. */
    public List<FileReadRecord> snapshotFiles(int limit) {
        List<FileReadRecord> out;
        synchronized (lock) {
            out = new ArrayList<>(files.values());
        }
        out.sort(Comparator.comparing(FileReadRecord::timestamp).reversed());
        if (limit > 0 && out.size() > limit) {
            return out.subList(0, limit);
        }
        return out;
    }

    /** Returns every recorded skill, newest first. */
    public List<SkillInvocationRecord> snapshotSkills() {
        List<SkillInvocationRecord> out;
        synchronized (lock) {
            out = new ArrayList<>(skills.values());
        }
        out.sort(Comparator.comparing(SkillInvocationRecord::timestamp).reversed());
        return out;
    }

    public PlanRecord snapshotPlan() {
        synchronized (lock) {
            return plan;
        }
    }

    /** Returns validation records oldest-to-newest, matching their execution order. */
    public List<ValidationRecord> snapshotValidations() {
        synchronized (lock) {
            return List.copyOf(validations);
        }
    }

    /** Returns permission decisions oldest-to-newest, matching the user's choices. */
    public List<ApprovalRecord> snapshotApprovals() {
        synchronized (lock) {
            return List.copyOf(approvals);
        }
    }
}
