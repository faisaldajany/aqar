package sa.waslah.agent.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The office whose data the current thread may touch. TenantDataSource copies it onto every
 * JDBC connection as the Postgres setting app.office_id, which the RLS policies read.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        UUID id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException("No office in context");
        }
        return id;
    }

    static void set(UUID officeId) {
        CURRENT.set(officeId);
    }

    static void clear() {
        CURRENT.remove();
    }

    /** Runs the block as the given office and restores the previous value afterwards. */
    public static <T> T call(UUID officeId, Supplier<T> block) {
        UUID previous = CURRENT.get();
        CURRENT.set(officeId);
        try {
            return block.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void run(UUID officeId, Runnable block) {
        call(officeId, () -> {
            block.run();
            return null;
        });
    }
}
