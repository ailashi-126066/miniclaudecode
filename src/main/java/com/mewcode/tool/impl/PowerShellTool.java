package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * PowerShell 工具：Windows 平台的命令执行
 */
public class PowerShellTool implements Tool {

    private static final int MAX_TIMEOUT = 600;
    private String workDir;

    public PowerShellTool() {
        this.workDir = null;
    }

    public PowerShellTool(String workDir) {
        this.workDir = workDir;
    }

    private static final String DESCRIPTION = """
            Execute a PowerShell command and return stdout and stderr (Windows only).

            IMPORTANT: Avoid using this tool to run Get-Content, Set-Content commands. \
            Instead use the dedicated ReadFile, EditFile, or WriteFile tools which provide a better experience.

            Usage notes:
            - The working directory persists between commands, but PowerShell state does not.
            - Always quote file paths containing spaces with double quotes.
            - Optional timeout in seconds (max 600). Default is 120s.
            - Use semicolon (;) to chain commands: cmd1; cmd2
            - Check success with $? variable after each command.

            PowerShell-specific:
            - Variables: $myVar = "value"
            - Cmdlets: Get-ChildItem, Set-Location, New-Item, Remove-Item
            - Pipeline: Get-Process | Where-Object {$_.CPU -gt 10}

            Avoid unnecessary Start-Sleep commands. Do not retry failing commands in a sleep loop.""";

    @Override
    public String name() {
        return "PowerShell";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of("type", "string", "description", "PowerShell command to execute"),
                                "timeout", Map.of("type", "integer", "description", "Timeout in seconds (max 600)", "default", 120)
                        ),
                        "required", List.of("command")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String command = getStringArg(args, "command", "");
        if (command.isEmpty()) {
            return ToolResult.error("Error: command is required");
        }

        int timeout = getIntArg(args, "timeout", 120);
        if (timeout > MAX_TIMEOUT) {
            timeout = MAX_TIMEOUT;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command);
            pb.redirectErrorStream(true);

            if (workDir != null && !workDir.isEmpty()) {
                pb.directory(new java.io.File(workDir));
            }

            Process process = pb.start();

            String output;
            try (InputStream stream = process.getInputStream()) {
                output = new String(stream.readAllBytes());
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("Error: command timed out after " + timeout + "s");
            }

            int exitCode = process.exitValue();

            var sb = new StringBuilder();
            if (!output.isEmpty()) {
                sb.append(output);
                if (!output.endsWith("\n")) {
                    sb.append('\n');
                }
            }

            if (exitCode != 0) {
                sb.append("[Exit code: ").append(exitCode).append("]");
            }

            return new ToolResult(sb.toString(), false);
        } catch (IOException e) {
            return ToolResult.error("Error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Error: command interrupted");
        }
    }

    // 辅助方法：从 args 提取字符串参数
    private static String getStringArg(Map<String, Object> args, String key, String defaultValue) {
        Object val = args.get(key);
        if (val instanceof String s) return s;
        return defaultValue;
    }

    // 辅助方法：从 args 提取整数参数
    private static int getIntArg(Map<String, Object> args, String key, int defaultValue) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultValue;
    }
}
