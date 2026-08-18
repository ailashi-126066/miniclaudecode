package com.mewcode.tool;
import java.time.Instant;
public record ToolExecutionRecord(String toolCallId,String toolName,ToolEffect effect,Status status,String argsHash,String beforeHash,String afterHash,String diffHash,String result,String runId,Instant updatedAt){public enum Status{PENDING,AWAITING_APPROVAL,COMPLETED,FAILED,UNKNOWN}}
