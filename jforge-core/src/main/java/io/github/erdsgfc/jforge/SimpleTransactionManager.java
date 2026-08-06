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
     */
    private static final class TxState {
        final DataSource dataSource;
        final Connection connection;

        TxState(DataSource dataSource, Connection connection) {
            this.dataSource = dataSource;
            this.connection = connection;
        }
    }

    private final ThreadLocal<TxState> tx = new ThreadLocal<>();

    /**
     * Thread-bound rollback-only flag: when {@code true} the transaction is rolled
     * back on completion instead of committed. Set through {@link #markRollbackOnly()}
     * and cleared in {@link #closeAndClear} so it never leaks across transactions.
     */
    private final ThreadLocal<Boolean> rollbackOnly = new ThreadLocal<>();

    @Override
    public Connection connection(DataSource dataSource) {
        TxState state = tx.get();
        if (state != null && state.dataSource == dataSource) {
            return state.connection;
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
        // transaction connection already decides whether to close. The parameter is
        // kept for signature symmetry so a Spring-backed TransactionManager can
        // delegate to DataSourceUtils.releaseConnection(conn, dataSource).
        TxState state = tx.get();
        if (state == null || conn != state.connection) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // best effort
            }
        }
    }

    @Override
    public void begin(DataSource dataSource) {
        if (tx.get() != null) {
            throw new OrmException("A transaction is already active on this thread");
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
        if (Boolean.TRUE.equals(rollbackOnly.get())) {
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
    public void markRollbackOnly() {
        if (tx.get() == null) {
            throw new OrmException("No active transaction to mark rollback-only");
        }
        rollbackOnly.set(true);
    }

    @Override
    public boolean isRollbackOnly() {
        return tx.get() != null && Boolean.TRUE.equals(rollbackOnly.get());
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
        rollbackOnly.remove();
        try {
            state.connection.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }
}
