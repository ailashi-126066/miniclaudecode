// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.skill;

/**
 * Resolves a disk-backed Skill into the exact instruction body that should be
 * added to a TUI conversation.
 *
 * <p>Both the {@code LoadSkill} tool and Slash Commands use this class so they
 * have identical refresh, validation, and argument-substitution behaviour.</p>
 */
public final class SkillActivator {

    public record Activation(String name, String body) {}

    private final SkillCatalog catalog;

    public SkillActivator(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    public Activation activate(String name, String args) {
        String normalizedName = name == null ? "" : name.strip();
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        if (catalog == null) {
            throw new IllegalStateException("Skill activation is not initialized (catalog is null)");
        }

        var skill = catalog.getFull(normalizedName)
                .orElseThrow(() -> new IllegalArgumentException("unknown skill: " + normalizedName));
        String body = SkillExecutor.substituteArguments(skill.promptBody(), args);
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException(
                    "skill \"" + normalizedName + "\" has empty body — cannot activate");
        }
        return new Activation(normalizedName, body);
    }
}
