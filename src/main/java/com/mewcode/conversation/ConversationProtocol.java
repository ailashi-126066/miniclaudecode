package com.mewcode.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provider-neutral safety checks for the internal conversation history.
 *
 * <p>This intentionally does not require a simplistic user/assistant alternation. Tool results
 * have provider-specific wire representations, while the invariant shared by every supported
 * provider is that each tool result must complete one earlier, still-pending tool use.</p>
 */
public final class ConversationProtocol {
    private ConversationProtocol() {}

    /**
     * Drops failed display-only messages, then verifies tool-use/result pairing before a provider
     * request is built. Tool-result messages are never dropped merely because their result is an
     * error: an errored tool execution is still the required response to the tool call.
     */
    public static List<Message> messagesForProvider(List<Message> source) {
        List<Message> retained = new ArrayList<>();
        for (Message message : source == null ? List.<Message>of() : source) {
            if (message == null) {
                continue;
            }
            boolean hasToolResults = message.getToolResults() != null && !message.getToolResults().isEmpty();
            if (message.getStatus() == MessageStatus.ERROR && !hasToolResults) {
                continue;
            }
            retained.add(message);
        }
        validateToolProtocol(retained);
        return List.copyOf(retained);
    }

    private static void validateToolProtocol(List<Message> messages) {
        Map<String, String> pending = new LinkedHashMap<>();
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            List<ToolResultBlock> results = message.getToolResults();
            boolean hasResults = results != null && !results.isEmpty();
            if (hasResults) {
                Set<String> seenHere = new LinkedHashSet<>();
                for (ToolResultBlock result : results) {
                    if (result == null || result.toolUseId() == null || result.toolUseId().isBlank()) {
                        throw new IllegalStateException("Tool result at message " + index + " has no tool_use_id");
                    }
                    if (!seenHere.add(result.toolUseId())) {
                        throw new IllegalStateException(
                                "Tool result at message " + index + " repeats tool_use_id " + result.toolUseId());
                    }
                    if (pending.remove(result.toolUseId()) == null) {
                        throw new IllegalStateException(
                                "Tool result at message " + index + " has no pending tool_use: " + result.toolUseId());
                    }
                }
                continue;
            }

            if (!pending.isEmpty()) {
                throw new IllegalStateException(
                        "Message " + index + " appears before tool results for: " + String.join(", ", pending.keySet()));
            }

            String role = message.getRole();
            if (!"user".equals(role) && !"assistant".equals(role)) {
                throw new IllegalStateException("Unsupported conversation role at message " + index + ": " + role);
            }
            if (!"assistant".equals(role) || message.getToolUses() == null) {
                continue;
            }
            for (ToolUseBlock use : message.getToolUses()) {
                if (use == null || use.toolUseId() == null || use.toolUseId().isBlank()) {
                    throw new IllegalStateException("Tool use at message " + index + " has no tool_use_id");
                }
                if (use.toolName() == null || use.toolName().isBlank()) {
                    throw new IllegalStateException("Tool use at message " + index + " has no tool name");
                }
                if (pending.putIfAbsent(use.toolUseId(), use.toolName()) != null) {
                    throw new IllegalStateException("Duplicate tool_use_id at message " + index + ": " + use.toolUseId());
                }
            }
        }
        if (!pending.isEmpty()) {
            throw new IllegalStateException("Missing tool results for: " + String.join(", ", pending.keySet()));
        }
    }
}
