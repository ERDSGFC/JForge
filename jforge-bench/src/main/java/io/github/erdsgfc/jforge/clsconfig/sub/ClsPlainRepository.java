package io.github.erdsgfc.jforge.clsconfig.sub;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/**
 * 子包仓库,无自身标注:批处理大小经包链继承父包
 * {@code io.github.erdsgfc.jforge.clsconfig} 的 package-info 配置(batchSize=3)。
 */
@Dao
public interface ClsPlainRepository extends BaseRepository<ClsEntity, Long> {
}
