package com.qin.orm.starter;

import com.qin.orm.annotation.Dao;
import com.qin.orm.core.BaseRepository;

/**
 * Test repository: inherits CRUD and the programmatic transaction contract
 * ({@code beginTransaction/commit/rollback/isTransactionActive/execute}) from
 * {@link BaseRepository}, implemented at compile time.
 */
@Dao
public interface TestUserRepository extends BaseRepository<TestUser, Long> {
}