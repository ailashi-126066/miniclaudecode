package com.mewcode.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRegistryUnregisterTest {

    @Test
    void removesDynamicCommandAndReleasesItsName() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command("temporary-skill", "", new String[]{},
                Command.CommandType.PROMPT, false), ctx -> "");

        assertTrue(registry.unregister("temporary-skill"));
        assertFalse(registry.find("temporary-skill").isPresent());

        registry.register(new Command("temporary-skill", "", new String[]{},
                Command.CommandType.PROMPT, false), ctx -> "");
        assertTrue(registry.find("temporary-skill").isPresent());
    }
}
