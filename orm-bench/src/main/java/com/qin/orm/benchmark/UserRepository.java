package com.qin.orm.benchmark;

import com.qin.orm.annotation.Dao;
import com.qin.orm.core.BaseRepository;

/** Benchmark repository: CRUD generated at compile time. */
@Dao
public interface UserRepository extends BaseRepository<UserEntity, Long> {
}
