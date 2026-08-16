package io.github.erdsgfc.jforge.clsconfig.sub;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.JForgeConfig;
import io.github.erdsgfc.jforge.core.BaseRepository;

/**
 * 子包仓库:接口自身标注 {@code @JForgeConfig(batchSize = 1)},
 * 覆盖父包 {@code io.github.erdsgfc.jforge.clsconfig} 的包级 batchSize=3。
 */
@Dao
@JForgeConfig(batchSize = 1)
public interface ClsRepository extends BaseRepository<ClsEntity, Long> {
}
