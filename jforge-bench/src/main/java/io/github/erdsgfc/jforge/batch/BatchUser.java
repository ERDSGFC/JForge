package io.github.erdsgfc.jforge.batch;

import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/**
 * Batch-test entity mapped to the {@code batch_users} table. The package's
 * {@code package-info} carries {@code @JForgeConfig(batchSize = 2)}.
 */
@Table(name = "batch_users")
public interface BatchUser {

    /** Database-generated primary key (BIGSERIAL). */
    @Id
    @GeneratedValue
    Long id();

    BatchUser id(Long id);

    /** Display name. */
    String name();

    BatchUser name(String name);
}