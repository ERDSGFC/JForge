package io.github.erdsgfc.jforge.convert;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/** 自定义类型转换实体（{@code @Convert}）的仓库。 */
@Dao
public interface ConvertUserRepository extends BaseRepository<ConvertUser, Long> {
}
