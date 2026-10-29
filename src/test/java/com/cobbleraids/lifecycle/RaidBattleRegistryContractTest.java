package com.cobbleraids.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Modifier;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RaidBattleRegistryContractTest {
    @Test
    void failureFallbackTargetsTheInstalledCobblemonRegistryField() throws Exception {
        // Do not initialize a game server. Verify the exceptional fallback's private-field contract
        // against the real dependency rather than a fabricated registry with the expected shape.
        Class<?> registry = Class.forName("com.cobblemon.mod.common.battles.BattleRegistry", false,
                getClass().getClassLoader());
        var field = registry.getDeclaredField("battleMap");
        assertTrue(Modifier.isStatic(field.getModifiers()));
        assertTrue(Map.class.isAssignableFrom(field.getType()));
        assertTrue(field.trySetAccessible());
    }
}
