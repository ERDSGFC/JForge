package io.github.erdsgfc.jforge.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 基于单个 {@code ThreadLocal} 的内置 {@link TransactionManager}。在未安装第三方事务管理器
 * （如 Spring）时使用。
 *
 * <p>线程局部变量持有单个 {@link State} 对象，内含两个可空槽位——事务状态与连接作用域状态。
 * 它们放在 <em>同一个</em> 槽位对象里，使热路径（{@link #connection(DataSource)} 与
 * {@link #release}）只需一次 {@code ThreadLocal.get()} 而非两次：无事务且无作用域激活时，
 * 查找落空一次即回退到连接池。两种状态可同时激活（一个数据源上开事务、另一个上开作用域），
 * 因此它们是各自独立的可空字段而非共用其一。</p>
 *
 * <p>线程状态同时持有连接及其获取来源的数据源：{@link #connection(DataSource)} 仅当请求的
 * 数据源与状态开启时所用的是同一实例时才返回共享的事务（或作用域）连接——实践中数据源是单例，
 * 因此身份比较既足够又比 {@code equals} 更廉价。绑定到不同数据源的仓库拿到自己的池化连接，
 * 保持在事务之外。</p>
 */
public final class SimpleTransactionManager implements TransactionManager {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleTransactionManager.class);

    /**
     * 线程绑定的事务状态：连接及其来源数据源。将它们绑在一起，可防止绑定到其他数据源的仓库
     * 意外地在当前事务的连接上执行 SQL。
     *
     * <p>{@code rollbackOnly} 放在状态对象上（而非独立的线程局部变量），因为它是事务的属性：
     * 只能在事务激活时设置，事务完成时随状态一起丢弃，因此绝不会跨事务泄漏。</p>
     */
    private static final class TxState {
        final DataSource dataSource;
        final Connection connection;
        boolean rollbackOnly;

        TxState(DataSource dataSource, Connection connection) {
            this.dataSource = dataSource;
            this.connection = connection;
        }
    }

    /**
     * 线程绑定的连接作用域状态：借用的连接及其来源数据源，与 {@link TxState} 镜像对应。
     * {@code depth} 统计同一数据源上嵌套的 {@link #beginScope(DataSource)} 调用次数，
     * 使内层作用域的 {@link #endScope(DataSource)} 不会过早释放外层作用域的连接。
     */
    private static final class ScopeState {
        final DataSource dataSource;
        final Connection connection;
        int depth;

        ScopeState(DataSource dataSource, Connection connection, int depth) {
            this.dataSource = dataSource;
            this.connection = connection;
            this.depth = depth;
        }
    }

    /**
     * 唯一的线程绑定槽位。两个字段均可空；任一、两者或都不设置都可能出现，因此不能共用一个
     * 字段。该对象只被所属线程访问，故无需 volatile。
     */
    private static final class State {
        TxState tx;
        ScopeState scope;
    }

    private final ThreadLocal<State> state = new ThreadLocal<>();

    @Override
    public Connection connection(DataSource dataSource) {
        State s = state.get();
        if (s != null) {
            TxState tx = s.tx;
            if (tx != null && tx.dataSource == dataSource) {
                return tx.connection;
            }
            ScopeState scoped = s.scope;
            if (scoped != null && scoped.dataSource == dataSource) {
                return scoped.connection;
            }
        }
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new JForgeException(JForgeException.Code.CONNECTION, "Cannot obtain connection", e);
        }
    }

    @Override
    public void release(Connection conn, DataSource dataSource) {
        // 此处未使用 dataSource：与线程绑定的事务或作用域连接做身份比较即可决定是否关闭。
        // 保留该参数是为了签名对称，使 Spring 版 TransactionManager 可以委托给
        // DataSourceUtils.releaseConnection(conn, dataSource)。
        State s = state.get();
        if (s != null) {
            TxState tx = s.tx;
            if (tx != null && conn == tx.connection) {
                return; // 连接归事务所有
            }
            ScopeState scoped = s.scope;
            if (scoped != null && conn == scoped.connection) {
                return; // 连接归作用域所有
            }
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
            // 尽力而为
        }
    }

    @Override
    public void begin(DataSource dataSource) {
        State s = state.get();
        if (s != null && s.tx != null) {
            throw new JForgeException(JForgeException.Code.TRANSACTION, "A transaction is already active on this thread");
        }
        if (s != null && s.scope != null) {
            // 把作用域的 auto-commit 连接升级为事务（之后再恢复）得不偿失；快速失败，
            // 让调用方重新调整作用域边界。
            throw new JForgeException(
                    JForgeException.Code.TRANSACTION,
                    "Cannot begin a transaction while a connection scope is active on this thread");
        }
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            if (s == null) {
                s = new State();
                state.set(s);
            }
            s.tx = new TxState(dataSource, conn);
            // 参数化占位符:仅 DEBUG 启用时才格式化,热路径零成本。
            LOG.debug("Transaction begun on {}", dataSource);
        } catch (SQLException e) {
            // setAutoCommit 失败时，已取得的连接不能泄漏：先关闭再重抛，
            // 否则连接池会永久少一个连接。
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                    // best effort
                }
            }
            throw new JForgeException(JForgeException.Code.TRANSACTION, "Cannot begin transaction", e);
        }
    }

    @Override
    public void commit() {
        State s = state.get();
        TxState tx = s != null ? s.tx : null;
        if (tx == null) {
            throw new JForgeException(JForgeException.Code.TRANSACTION, "No active transaction to commit");
        }
        if (tx.rollbackOnly) {
            // 事务已被标记为 rollback-only：不提交而直接丢弃，且不抛出——调用方已正常返回。
            rollback();
            return;
        }
        try {
            tx.connection.commit();
            LOG.debug("Transaction committed on {}", tx.dataSource);
        } catch (SQLException e) {
            throw new JForgeException(JForgeException.Code.TRANSACTION, "Commit failed", e);
        } finally {
            closeAndClear(s, tx);
        }
    }

    @Override
    public void rollback() {
        State s = state.get();
        TxState tx = s != null ? s.tx : null;
        if (tx == null) {
            throw new JForgeException(JForgeException.Code.TRANSACTION, "No active transaction to rollback");
        }
        try {
            tx.connection.rollback();
            LOG.debug("Transaction rolled back on {}", tx.dataSource);
        } catch (SQLException e) {
            throw new JForgeException(JForgeException.Code.TRANSACTION, "Rollback failed", e);
        } finally {
            closeAndClear(s, tx);
        }
    }

    @Override
    public boolean isActive() {
        State s = state.get();
        return s != null && s.tx != null;
    }

    @Override
    public Connection beginScope(DataSource dataSource) {
        State s = state.get();
        if (s != null) {
            TxState tx = s.tx;
            if (tx != null && tx.dataSource == dataSource) {
                // 线程上已有活动事务共享其连接：作用域退化为 no-op。endScope() 也必须为
                // no-op——事实正是如此，因为没有绑定任何作用域状态。
                return tx.connection;
            }
            ScopeState existing = s.scope;
            if (existing != null) {
                if (existing.dataSource != dataSource) {
                    throw new JForgeException(
                            JForgeException.Code.CONNECTION,
                            "A connection scope is already active on this thread for a different data source");
                }
                existing.depth++;
                return existing.connection; // 嵌套作用域：复用外层连接
            }
        }
        try {
            Connection conn = dataSource.getConnection();
            if (s == null) {
                s = new State();
                state.set(s);
            }
            s.scope = new ScopeState(dataSource, conn, 1);
            LOG.debug("Connection scope begun on {}", dataSource);
            return conn;
        } catch (SQLException e) {
            throw new JForgeException(JForgeException.Code.CONNECTION, "Cannot obtain connection", e);
        }
    }

    @Override
    public void endScope(DataSource dataSource) {
        State s = state.get();
        ScopeState scoped = s != null ? s.scope : null;
        if (scoped == null) {
            return; // no-op：作用域已并入事务，或当前无活动作用域
        }
        if (scoped.dataSource != dataSource) {
            throw new JForgeException(JForgeException.Code.CONNECTION, "Connection scope data source mismatch");
        }
        if (--scoped.depth > 0) {
            return; // 仍处在本线程的外层作用域内
        }
        s.scope = null;
        if (s.tx == null) {
            state.remove(); // 两个槽位都为空：丢弃槽位，使线程不会保留 State
        }
        LOG.debug("Connection scope ended on {}", dataSource);
        try {
            scoped.connection.close();
        } catch (SQLException ignored) {
            // 尽力而为
        }
    }

    @Override
    public void markRollbackOnly() {
        State s = state.get();
        TxState tx = s != null ? s.tx : null;
        if (tx == null) {
            throw new JForgeException(JForgeException.Code.TRANSACTION, "No active transaction to mark rollback-only");
        }
        tx.rollbackOnly = true;
        LOG.debug("Transaction marked rollback-only on {}", tx.dataSource);
    }

    @Override
    public boolean isRollbackOnly() {
        State s = state.get();
        return s != null && s.tx != null && s.tx.rollbackOnly;
    }

    /**
     * 将事务从线程状态中摘除并关闭连接。在 commit/rollback 的 {@code finally} 中调用，确保线程
     * 绝不泄漏事务——即使 JDBC 调用本身失败。仅当作用域槽位也为空时才移除线程槽位，
     * 以便并发激活的作用域保留其状态。
     *
     * @param s  线程状态（此处不可能为 null）
     * @param tx 要清除的事务状态
     */
    private void closeAndClear(State s, TxState tx) {
        s.tx = null;
        if (s.scope == null) {
            state.remove();
        }
        try {
            tx.connection.close();
        } catch (SQLException ignored) {
            // 尽力而为
        }
    }
}
