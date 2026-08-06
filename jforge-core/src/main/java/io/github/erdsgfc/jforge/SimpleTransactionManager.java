package io.github.erdsgfc.jforge;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Built-in {@link TransactionManager} backed by a {@code ThreadLocal}.
 * Used when no third-party transaction manager (e.g. Spring) is installed.
 *
 * <p>The thread-local holds both the transaction connection and the data source
 * it was acquired from. {@link #connection(DataSource)} returns the shared
 * transaction connection only when the requested data source is the same
 * instance the transaction was begun on — data sources are singletons in
 * practice, so an identity comparison is both sufficient and cheaper than
 * {@code equals}. A repository bound to a different data source gets its own
 * pooled connection and stays outside the transaction.</p>
 */
public final class SimpleTransactionManager implements TransactionManager {

    /**
     * Thread-bound transaction state: the connection plus the data source it
     * came from. Tying them together prevents a repository bound to another
     * data source from accidentally running its SQL on this transaction's
     * connection.
     *
     * <p>{@code rollbackOnly} lives on the state (not in a thread-local of its
     * own) because it is a property of the transaction: it can only be set while
     * a transaction is active and is discarded together with the state when the
     * transaction completes, so it can never leak across transactions.</p>
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

    private final ThreadLocal<TxState> tx = new ThreadLocal<>();

    /**
     * Thread-bound connection-scope state: the borrowed connection plus the data
     * source it came from, mirroring {@link TxState}. {@code depth} counts nested
     * {@link #beginScope(DataSource)} calls on the same data source so an inner
     * scope's {@link #endScope(DataSource)} does not release the outer scope's
     * connection early.
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

    private final ThreadLocal<ScopeState> scope = new ThreadLocal<>();

    @Override
    public Connection connection(DataSource dataSource) {
        TxState state = tx.get();
        if (state != null && state.dataSource == dataSource) {
            return state.connection;
        }
        ScopeState scoped = scope.get();
        if (scoped != null && scoped.dataSource == dataSource) {
            return scoped.connection;
        }
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new OrmException("Cannot obtain connection", e);
        }
    }

    @Override
    public void release(Connection conn, DataSource dataSource) {
        // dataSource is unused here: an identity comparison with the thread-bound
        // transaction or scope connection already decides whether to close. The
        // parameter is kept for signature symmetry so a Spring-backed
        // TransactionManager can delegate to DataSourceUtils.releaseConnection(conn, dataSource).
        TxState state = tx.get();
        ScopeState scoped = scope.get();
        if (state != null && conn == state.connection) {
            return; // the transaction owns the connection
        }
        if (scoped != null && conn == scoped.connection) {
            return; // the scope owns the connection
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }

    @Override
    public void begin(DataSource dataSource) {
        if (tx.get() != null) {
            throw new OrmException("A transaction is already active on this thread");
        }
        if (scope.get() != null) {
            // Upgrading the scope's auto-commit connection into a transaction (and
            // restoring it afterwards) is more complexity than it is worth; fail
            // fast so the caller restructures the scope boundaries.
            throw new OrmException(
                    "Cannot begin a transaction while a connection scope is active on this thread");
        }
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            tx.set(new TxState(dataSource, conn));
        } catch (SQLException e) {
            // A connection already obtained must not leak when setAutoCommit fails:
            // close it before rethrowing, or the pool is permanently short one connection.
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                    // best effort
                }
            }
            throw new OrmException("Cannot begin transaction", e);
        }
    }

    @Override
    public void commit() {
        TxState state = tx.get();
        if (state == null) {
            throw new OrmException("No active transaction to commit");
        }
        if (state.rollbackOnly) {
            // The transaction was marked rollback-only: discard it instead of
            // committing, without throwing — the caller returned normally.
            rollback();
            return;
        }
        try {
            state.connection.commit();
        } catch (SQLException e) {
            throw new OrmException("Commit failed", e);
        } finally {
            closeAndClear(state);
        }
    }

    @Override
    public void rollback() {
        TxState state = tx.get();
        if (state == null) {
            throw new OrmException("No active transaction to rollback");
        }
        try {
            state.connection.rollback();
        } catch (SQLException e) {
            throw new OrmException("Rollback failed", e);
        } finally {
            closeAndClear(state);
        }
    }

    @Override
    public boolean isActive() {
        return tx.get() != null;
    }

    @Override
    public Connection beginScope(DataSource dataSource) {
        TxState state = tx.get();
        if (state != null && state.dataSource == dataSource) {
            // An active transaction already shares its connection on this thread:
            // the scope becomes a no-op. endScope() must then also be a no-op —
            // which it is, because no scope state was bound.
            return state.connection;
        }
        ScopeState existing = scope.get();
        if (existing != null) {
            if (existing.dataSource != dataSource) {
                throw new OrmException(
                        "A connection scope is already active on this thread for a different data source");
            }
            existing.depth++;
            return existing.connection; // nested scope: reuse the outer connection
        }
        try {
            Connection conn = dataSource.getConnection();
            scope.set(new ScopeState(dataSource, conn, 1));
            return conn;
        } catch (SQLException e) {
            throw new OrmException("Cannot obtain connection", e);
        }
    }

    @Override
    public void endScope(DataSource dataSource) {
        ScopeState state = scope.get();
        if (state == null) {
            return; // no-op: the scope joined a transaction, or no scope is active
        }
        if (state.dataSource != dataSource) {
            throw new OrmException("Connection scope data source mismatch");
        }
        if (--state.depth > 0) {
            return; // still inside an outer scope on this thread
        }
        scope.remove();
        try {
            state.connection.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }

    @Override
    public void markRollbackOnly() {
        TxState state = tx.get();
        if (state == null) {
            throw new OrmException("No active transaction to mark rollback-only");
        }
        state.rollbackOnly = true;
    }

    @Override
    public boolean isRollbackOnly() {
        TxState state = tx.get();
        return state != null && state.rollbackOnly;
    }

    /**
     * Detaches the thread-local state and closes the connection. Called in the
     * {@code finally} of commit/rollback so the thread never leaks a transaction
     * — even when the JDBC call itself fails.
     *
     * @param state the transaction state to clear
     */
    private void closeAndClear(TxState state) {
        tx.remove();
        try {
            state.connection.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }
}
