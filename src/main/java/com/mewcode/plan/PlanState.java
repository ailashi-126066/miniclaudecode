package com.mewcode.plan;

import java.time.Instant;
import java.util.List;

public record PlanState(String id,String goal,Status status,List<Step> steps,Instant updatedAt) {
    public enum Status { DRAFT, ACTIVE, COMPLETED, BLOCKED }
    public enum StepStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED }
    public record Evidence(List<String> toolResults,List<String> verificationResults,List<String> changedFiles,String failureReason,Instant recordedAt) {}
    public record Step(String id,String description,List<String> dependsOn,List<String> acceptanceCriteria,boolean requiresVerification,StepStatus status,int attempts,Evidence evidence) {}
}
