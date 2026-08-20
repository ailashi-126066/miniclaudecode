// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class ExitPlanModeTool implements Tool {

    private static final String DESCRIPTION =
            "Exit plan mode and present the plan for user approval. "
                    + "Call this when your plan is complete and written to the plan file.";

    private BooleanSupplier isPlanMode;
    private BooleanSupplier planExists;
    private Supplier<String> planContent;

    public void setIsPlanMode(BooleanSupplier isPlanMode) {
        this.isPlanMode = isPlanMode;
    }

    public void setPlanExists(BooleanSupplier planExists) {
        this.planExists = planExists;
    }

    public void setPlanContent(Supplier<String> planContent) {
        this.planContent = planContent;
    }

    @Override
    public String name() {
        return "ExitPlanMode";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of()
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        if (isPlanMode != null && !isPlanMode.getAsBoolean()) {
            return new ToolResult(
                    "You are not in plan mode. This tool is only for exiting plan mode after writing a plan.",
                    true);
        }
        if (planExists != null && !planExists.getAsBoolean()) {
            return new ToolResult(
                    "No plan file found. Please write your plan to the plan file before calling ExitPlanMode.",
                    true);
        }
        String content = planContent == null ? "" : planContent.get();
        if (planContent != null && (content == null || content.isBlank())) {
            return new ToolResult("The active plan is empty. Create a structured plan before calling ExitPlanMode.", true);
        }
        String approvalMessage = content == null || content.isBlank()
                ? "Plan mode will be exited after this turn.\n\n"
                : "Plan ready for user approval:\n\n" + content + "\n\n";
        return new ToolResult(
                approvalMessage
                        + "The user will be shown the plan approval dialog. "
                        + "Do not call any more tools — end your turn now.",
                false);
    }
}
