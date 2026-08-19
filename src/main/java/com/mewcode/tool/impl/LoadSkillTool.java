// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tool.impl;

import com.mewcode.skill.SkillCatalog;
import com.mewcode.skill.SkillActivator;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 按名称激活 Skill，将完整 SOP 注入对话上下文。
 * <p>
 * 系统提示词里只列出 Skill 的名称和一句话描述（渐进式披露），
 * 模型根据用户意图调用本工具加载完整指令。
 */
public class LoadSkillTool implements Tool {

    private static final String DESCRIPTION =
            "Activate a Skill by name. Returns the full SOP body so you can follow its "
            + "instructions. Call this when the user's request matches one of the available "
            + "Skills listed in the system prompt. Pass the Skill name without a leading slash.";

    private SkillActivator activator;
    // (name, body) → 注入对话上下文
    private BiConsumer<String, String> onActivate;

    public void setCatalog(SkillCatalog catalog) {
        this.activator = catalog != null ? new SkillActivator(catalog) : null;
    }

    public void setActivator(SkillActivator activator) { this.activator = activator; }

    public void setOnActivate(BiConsumer<String, String> callback) { this.onActivate = callback; }

    @Override
    public String name() { return "LoadSkill"; }

    @Override
    public String description() { return DESCRIPTION; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of(
                                        "type", "string",
                                        "description",
                                        "The Skill name to activate (e.g. \"commit\", \"backend-interview\")."
                                )
                        ),
                        "required", List.of("name")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String name = args.getOrDefault("name", "").toString();
        if (activator == null) {
            return ToolResult.error("LoadSkill not initialized (catalog is null)");
        }
        try {
            var activation = activator.activate(name, "");
            if (onActivate != null) {
                onActivate.accept(activation.name(), activation.body());
            }
            // The full body is already pinned by onActivate. Returning it again
            // would duplicate a potentially large SOP in the conversation.
            return ToolResult.success("Skill \"" + activation.name() + "\" activated.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
