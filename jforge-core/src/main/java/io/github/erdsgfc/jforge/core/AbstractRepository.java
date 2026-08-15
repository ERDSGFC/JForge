package io.github.erdsgfc.jforge.core;

import io.github.erdsgfc.jforge.TransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 生成仓库实现的公共父类：持有 {@code protected final} 的 {@link DataSource} 与
 * {@link TransactionManager}，并提供连接访问与编程式事务/连接作用域方法。
 *
 * <p>这些能力与实体无关，下沉到本类让注解处理器生成的 {@code XxxRepository_Impl} 只保留
 * 实体特定代码（SQL 常量、行映射、CRUD、{@code @Query}），从而：① 生成代码量减少约 1/4（仓库
 * 多时编译更快、生成源码更清晰）；② 连接/事务逻辑在框架里写一次、统一维护；③ 字节码不重复。
 * 事务方法标 {@code final}——它们语义固定、不应被覆盖，也利于 JIT 内联。</p>
 *
 * <p>生成的 impl 经 {@code super(dataSource, transactionManager)} 传入依赖；门面
 * {@code JForge} 与工厂 {@code Repositories.create(Class, DataSource, TransactionManager)}
 * 负责在构造时传入对应的管理器（构造器注入，无全局静态查找）。</p>
 */
public abstract class AbstractRepository implements TransactionOperations {

    protected final DataSource dataSource;

    protected final TransactionManager transactionManager;

    /**
     * @param dataSource         the data source the repository reads/writes through
     * @param transactionManager the transaction manager the repository delegates to
     */
    protected AbstractRepository(DataSource dataSource, TransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
    }

    /**
     * Returns the thread-bound connection: the shared transaction connection while a
     * transaction is active, otherwise a fresh pooled connection.
     *
     * @return a usable {@link Connection}
     */
    protected final Connection getConnection() {
        return transactionManager.connection(dataSource);
    }

    /**
     * Releases a connection obtained via {@link #getConnection()}: closes it unless it
     * is the transaction-bound connection, which stays open until commit/rollback.
     *
     * @param conn the connection to release
     */
    protected final void releaseConnection(Connection conn) {
        transactionManager.release(conn, dataSource);
    }

    @Override
    public final Connection beginTransaction() {
        transactionManager.begin(dataSource);
        return getConnection();
    }

    @Override
    public final void commit() {
        transactionManager.commit();
    }

    @Override
    public final void rollback() {
        transactionManager.rollback();
    }

    @Override
    public final boolean isTransactionActive() {
        return transactionManager.isActive();
    }

    @Override
    public final void markRollbackOnly() {
        transactionManager.markRollbackOnly();
    }

    @Override
    public final boolean isRollbackOnly() {
        return transactionManager.isRollbackOnly();
    }

    @Override
    public final Connection beginConnectionScope() {
        return transactionManager.beginScope(dataSource);
    }

    @Override
    public final void endConnectionScope() {
        transactionManager.endScope(dataSource);
    }
}
