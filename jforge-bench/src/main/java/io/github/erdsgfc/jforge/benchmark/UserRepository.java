package io.github.erdsgfc.jforge.benchmark;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/** 基准仓库:CRUD 在编译期生成。 */
@Dao
public interface UserRepository extends BaseRepository<UserEntity, Long> {
}
