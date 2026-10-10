package de.grouppurse.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Hält die aktive Wohngruppe des aktuellen Threads. Ohne Wert wird der Sentinel NONE verwendet (schlägt am FK fehl). */
public final class TenantContext {
    public static final UUID NONE = new UUID(0L, 0L);
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenant) {
        CURRENT.set(tenant);
    }

    public static Optional<UUID> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static <T> T callAs(UUID tenant, Supplier<T> action) {
        UUID previous = CURRENT.get();
        CURRENT.set(tenant);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAs(UUID tenant, Runnable action) {
        callAs(tenant, () -> {
            action.run();
            return null;
        });
    }
}
