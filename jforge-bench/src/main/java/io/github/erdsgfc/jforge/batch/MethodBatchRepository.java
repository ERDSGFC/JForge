package io.github.erdsgfc.jforge.batch;

import io.github.erdsgfc.jforge.annotation.BatchSize;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

import java.util.List;

/**
 * 按方法覆盖批处理大小的批处理测试仓库:{@code save(List)}
 * 以相同签名重新声明(相对 {@code BaseRepository} 合法),
 * 携带 {@code @BatchSize(5)},处理器优先于类型级与配置值解析它。
 */
@Dao
public interface MethodBatchRepository extends BaseRepository<BatchUser, Long> {

    @BatchSize(5)
    @Override
    List<BatchUser> save(List<BatchUser> entities);
}