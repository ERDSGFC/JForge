package io.github.erdsgfc.jforge.batch;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/**
 * Batch-test repository: uses the global batch size from {@link BatchConfig}
 * ({@code @JForgeConfig(batchSize = 2)},标在普通类上,对整个编译生效)。
 */
@Dao
public interface PackageBatchRepository extends BaseRepository<BatchUser, Long> {
}