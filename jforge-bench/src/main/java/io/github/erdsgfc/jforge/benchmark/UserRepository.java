package io.github.erdsgfc.jforge.benchmark;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/** Benchmark repository: CRUD generated at compile time. */
@Dao
public interface UserRepository extends BaseRepository<UserEntity, Long> {
}
