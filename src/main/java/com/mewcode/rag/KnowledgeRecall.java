package com.mewcode.rag;

import com.mewcode.rag.search.SearchResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Fast, local, best-effort recall for requests likely to need private knowledge. */
public final class KnowledgeRecall {
    private static final Set<String> KEYWORDS = Set.of(
            "requirement", "requirements", "spec", "specification", "policy", "rule", "protocol",
            "api", "contract", "business", "domain", "implementation", "implement", "fix", "debug",
            "refactor", "需求", "规范", "规则", "协议", "接口", "业务", "领域", "实现", "修复", "调试", "重构");

    private KnowledgeRecall() {}

    public static CompletableFuture<String> prefetch(Path workspace, String query, String mode) {
        if (!shouldRetrieve(query, mode)) return CompletableFuture.completedFuture("");
        return CompletableFuture.supplyAsync(() -> {
            try {
                KnowledgeRagService service = new KnowledgeRagService(workspace);
                var status = service.status();
                if (!status.indexed() || status.stale()) return "";
                return render(service.search(query).results());
            } catch (Exception ignored) {
                return "";
            }
        }, runnable -> Thread.ofVirtual().name("knowledge-recall-prefetch").start(runnable));
    }

    static boolean shouldRetrieve(String query, String mode) {
        String value = query == null ? "" : query.strip();
        if (value.isEmpty() || value.startsWith("/")) return false;
        String normalizedMode = mode == null ? "auto" : mode.strip().toLowerCase(Locale.ROOT);
        if ("off".equals(normalizedMode)) return false;
        if ("always".equals(normalizedMode)) return true;
        if (value.contains("```") || value.matches("(?s).*\\b[\\w.-]+\\.(java|py|js|ts|go|rs|cs|md)\\b.*")) return true;
        String lower = value.toLowerCase(Locale.ROOT);
        return KEYWORDS.stream().anyMatch(lower::contains);
    }

    private static String render(List<SearchResult> results) {
        if (results == null || results.isEmpty()) return "";
        StringBuilder out = new StringBuilder("## Relevant private knowledge\n");
        int included = 0;
        for (SearchResult result : results) {
            if (included == 3) break;
            var chunk = result.chunk();
            String content = chunk.content();
            if (content.length() > 1200) content = content.substring(0, 1200) + "\n...";
            out.append("\n### ").append(chunk.path()).append(':').append(chunk.startLine())
                    .append('-').append(chunk.endLine()).append("\n").append(content).append('\n');
            included++;
        }
        return included == 0 ? "" : out.toString();
    }
}
