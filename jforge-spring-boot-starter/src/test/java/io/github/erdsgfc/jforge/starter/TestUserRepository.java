package io.github.erdsgfc.jforge.starter;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/**
 * 测试仓库：从 {@link BaseRepository} 继承 CRUD 与编程式事务契约
 * （{@code beginTransaction/commit/rollback/isTransactionActive/execute}），
 * 在编译期实现。
 */
@Dao
public interface TestUserRepository extends BaseRepository<TestUser, Long> {
}