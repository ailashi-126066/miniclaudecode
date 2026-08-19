package com.mewcode.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillActivatorTest {

    @TempDir
    Path tempDir;

    @Test
    void readsLatestBodyAndSubstitutesArguments() throws Exception {
        Path skillDir = Files.createDirectories(tempDir.resolve("skills/review"));
        Path skillFile = skillDir.resolve("SKILL.md");
        Files.writeString(skillFile, "---\nname: review\n---\nReview $ARGUMENTS");

        SkillCatalog catalog = new SkillCatalog();
        catalog.loadFromDirectory(tempDir.resolve("skills"));
        SkillActivator activator = new SkillActivator(catalog);

        assertEquals("Review auth module", activator.activate("review", "auth module").body());

        Files.writeString(skillFile, "---\nname: review\n---\nInspect $ARGUMENTS");
        assertEquals("Inspect auth module", activator.activate("review", "auth module").body());
    }

    @Test
    void appendsArgumentsWhenSkillHasNoPlaceholder() {
        SkillCatalog catalog = new SkillCatalog();
        var meta = new SkillCatalog.SkillMeta("review", "", "", java.util.List.of(), "inline", "", "none");
        catalog.register(new SkillCatalog.Skill(meta, "Review the change.", null, true));

        String body = new SkillActivator(catalog).activate("review", "focus on tests").body();

        assertTrue(body.contains("## User Request"));
        assertTrue(body.endsWith("focus on tests"));
    }

    @Test
    void rejectsUnknownSkill() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new SkillActivator(new SkillCatalog()).activate("missing", ""));

        assertEquals("unknown skill: missing", error.getMessage());
    }
}
