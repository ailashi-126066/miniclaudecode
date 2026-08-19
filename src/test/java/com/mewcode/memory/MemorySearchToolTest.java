package com.mewcode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySearchToolTest {

    @Test
    void searchesMarkdownMemoryContent(@TempDir Path workspace) throws Exception {
        Path memoryDir = workspace.resolve(".mewcode/memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("user.md"), """
                ---
                name: user
                description: User memories
                metadata:
                  type: user
                ---

                # User memories

                ## response-style

                Description: Keep answers concise

                Reply with short, direct Chinese explanations.
                """);

        var result = new MemorySearchTool(memoryDir)
                .execute(Map.of("query", "direct Chinese"));

        assertFalse(result.isError());
        assertTrue(result.output().contains("response-style"));
        assertTrue(result.output().contains("short, direct Chinese"));
    }
}
