// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.conversation;

import java.util.List;
import java.util.UUID;

public class Message {

    private final String id;
    private MessageStatus status;
    private final long createdAt;
    private UsageInfo usage;
    private Long responseTimeMs;

    private String role;
    private String content;

    private List<ThinkingBlock> thinkingBlocks;
    private List<ToolUseBlock> toolUses;

    private List<ToolResultBlock> toolResults;

    public Message(String role, String content) {
        this.id = UUID.randomUUID().toString();
        this.status = MessageStatus.COMPLETE;
        this.createdAt = System.currentTimeMillis();
        this.role = role;
        this.content = content;
    }

    public String getId() { return id; }
    public MessageStatus getStatus() { return status; }
    public void setStatus(MessageStatus status) { this.status = status == null ? MessageStatus.COMPLETE : status; }
    public long getCreatedAt() { return createdAt; }
    public UsageInfo getUsage() { return usage; }
    public void setUsage(UsageInfo usage) { this.usage = usage; }
    public Long getResponseTimeMs() { return responseTimeMs; }
    public void setResponseTimeMs(Long responseTimeMs) {
        this.responseTimeMs = responseTimeMs == null || responseTimeMs < 0 ? null : responseTimeMs;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<ThinkingBlock> getThinkingBlocks() { return thinkingBlocks; }

    public void setThinkingBlocks(List<ThinkingBlock> thinkingBlocks) { this.thinkingBlocks = thinkingBlocks; }

    public List<ToolUseBlock> getToolUses() { return toolUses; }

    public void setToolUses(List<ToolUseBlock> toolUses) { this.toolUses = toolUses; }

    public List<ToolResultBlock> getToolResults() { return toolResults; }
    public void setToolResults(List<ToolResultBlock> toolResults) { this.toolResults = toolResults; }
}
