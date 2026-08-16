package io.github.erdsgfc.jforge.batch;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/**
 * 批处理测试仓库:使用 {@link BatchConfig} 上的全局批处理大小
 * ({@code @JForgeConfig(batchSize = 2)},标在普通类上,对整个编译生效)。
 */
@Dao
public interface PackageBatchRepository extends BaseRepository<BatchUser, Long> {
}