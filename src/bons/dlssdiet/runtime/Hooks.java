package bons.dlssdiet.runtime;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Whether the Oculus hooks are on (the mixin plugin's decision) and which ones were applied so far. A mixin is applied
 * when its target class loads, so at the first shader pack load Oculus' CommonUniforms is usually not loaded yet: the
 * runtime trusts the plugin then and checks at the first DLSS dispatch that the jitter uniform was really registered.
 * Deliberately free of Minecraft/Oculus references: touching those during mixin application would load classes before
 * their mixins.
 */
public final class Hooks {
    private static final Set<String> APPLIED = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean enabled;

    private Hooks() {
    }

    public static void enabled(boolean on) {
        enabled = on;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void applied(String mixinSimpleName) {
        APPLIED.add(mixinSimpleName);
    }

    public static String describe() {
        synchronized (APPLIED) {
            return APPLIED.toString();
        }
    }
}
