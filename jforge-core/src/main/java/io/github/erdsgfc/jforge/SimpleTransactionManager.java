package io.github.erdsgfc.jforge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Built-in {@link TransactionManager} backed by a single {@code ThreadLocal}.
 * Used when no third-party transaction manager (e.g. Spring) is installed.
 *
 * <p>The thread-local holds one {@link State} object with two nullable slots —
 * the transaction state and the connection-scope state. They live in <em>one</em>
 * slot so the hot path ({@link #connection(DataSource)} and {@link #release})
 * pays a single {@code ThreadLocal.get()} instead of two: with no transaction and
 * no scope active the lookup misses once and falls through to the pool. The two
 * states can be active simultaneously (a transaction on one data source and a
 * scope on another), which is why they are separate nullable fields rather than
 * one of them.</p>
 *
 * <p>The thread state holds both the connection and the data source it was
 * acquired from: {@link #connection(DataSource)} returns the shared transaction
 * (or scope) connection only when the requested data source is the same instance
 * the state was begun on — data sources are singletons in practice, so an
 * identity comparison is both sufficient and cheaper than {@code equals}. A
 * repository bound to a different data source gets its own pooled connection and
 * stays outside the transaction.</p>
 */
public final class SimpleTransactionManager implements TransactionManager {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleTransactionManager.class);

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

    /**
     * The single thread-bound slot. Both fields are nullable; either, both or
     * neither can be set, which is why they cannot share one field. The object
     * itself is only ever touched by the owning thread, so no volatile is needed.
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
        // dataSource is unused here: an identity comparison with the thread-bound
        // transaction or scope connection already decides whether to close. The
        // parameter is kept for signature symmetry so a Spring-backed
        // TransactionManager can delegate to DataSourceUtils.releaseConnection(conn, dataSource).
        State s = state.get();
        if (s != null) {
            TxState tx = s.tx;
            if (tx != null && conn == tx.connection) {
                return; // the transaction owns the connection
            }
            ScopeState scoped = s.scope;
            if (scoped != null && conn == scoped.connection) {
                return; // the scope owns the connection
            }
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }

    @Override
    public void begin(DataSource dataSource) {
        State s = state.get();
        if (s != null && s.tx != null) {
            throw new JForgeException(JForgeException.Code.TRANSACTION, "A transaction is already active on this thread");
        }
        if (s != null && s.scope != null) {
            // Upgrading the scope's auto-commit connection into a transaction (and
            // restoring it afterwards) is more complexity than it is worth; fail
            // fast so the caller restructures the scope boundaries.
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
            LOG.debug("Transaction begun");
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
            // The transaction was marked rollback-only: discard it instead of
            // committing, without throwing — the caller returned normally.
            rollback();
            return;
        }
        try {
            tx.connection.commit();
            LOG.debug("Transaction committed");
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
            LOG.debug("Transaction rolled back");
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
                // An active transaction already shares its connection on this thread:
                // the scope becomes a no-op. endScope() must then also be a no-op —
                // which it is, because no scope state was bound.
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
                return existing.connection; // nested scope: reuse the outer connection
            }
        }
        try {
            Connection conn = dataSource.getConnection();
            if (s == null) {
                s = new State();
                state.set(s);
            }
            s.scope = new ScopeState(dataSource, conn, 1);
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
            return; // no-op: the scope joined a transaction, or no scope is active
        }
        if (scoped.dataSource != dataSource) {
            throw new JForgeException(JForgeException.Code.CONNECTION, "Connection scope data source mismatch");
        }
        if (--scoped.depth > 0) {
            return; // still inside an outer scope on this thread
        }
        s.scope = null;
        if (s.tx == null) {
            state.remove(); // both slots empty: drop the slot so the thread never retains the State
        }
        try {
            scoped.connection.close();
        } catch (SQLException ignored) {
            // best effort
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
    }

    @Override
    public boolean isRollbackOnly() {
        State s = state.get();
        return s != null && s.tx != null && s.tx.rollbackOnly;
    }

    /**
     * Detaches the transaction from the thread state and closes the connection.
     * Called in the {@code finally} of commit/rollback so the thread never leaks a
     * transaction — even when the JDBC call itself fails. The thread slot is only
     * removed when the scope slot is empty too, so a concurrently active scope
     * keeps its state.
     *
     * @param s  the thread state (never null here)
     * @param tx the transaction state to clear
     */
    private void closeAndClear(State s, TxState tx) {
        s.tx = null;
        if (s.scope == null) {
            state.remove();
        }
        try {
            tx.connection.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }
}
