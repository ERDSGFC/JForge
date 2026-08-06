package io.github.erdsgfc.jforge;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Pluggable transaction manager — the single extension point for replacing the
 * built-in thread-local transaction with Spring's {@code PlatformTransactionManager}.
 *
 * <p>Generated repository implementations call the methods on
 * {@link #current()} — never the concrete implementation directly.  A Spring
 * Boot starter can swap the global singleton with a Spring-aware version at
 * startup time, without any changes to the generated code.</p>
 */
public interface TransactionManager {

    /** Returns the current transaction connection, or a fresh auto-commit connection from the pool. */
    Connection connection(DataSource dataSource);

    /**
     * Releases a connection obtained via {@link #connection(DataSource)}.
     *
     * <p>The data source is passed so a Spring-backed implementation can delegate
     * to {@code DataSourceUtils.releaseConnection(conn, dataSource)} — which needs
     * the data source to decide whether the connection is the transaction-bound one
     * (kept open for the transaction) or a pooled connection to hand back.</p>
     *
     * @param conn       the connection to release
     * @param dataSource the data source the connection was obtained from
     */
    void release(Connection conn, DataSource dataSource);

    /** Starts a new transaction. */
    void begin(DataSource dataSource);

    /** Commits the active transaction. */
    void commit();

    /** Rolls back the active transaction. */
    void rollback();

    /** Returns {@code true} when a transaction is active on this thread. */
    boolean isActive();

    /**
     * Marks the active transaction for rollback without throwing: the transaction is
     * rolled back when it completes even if the caller returns normally. Used to
     * abort on a business rule while still returning a result from a transactional
     * callback.
     *
     * @throws OrmException if no transaction is active on this thread
     */
    void markRollbackOnly();

    /**
     * Returns whether the active transaction has been marked for rollback via
     * {@link #markRollbackOnly()}.
     *
     * @return {@code true} when the active transaction is marked for rollback
     */
    boolean isRollbackOnly();

    // ---- Connection scope (no transaction) ----------------------------------

    /**
     * Begins a connection scope on the current thread: borrows a single connection
     * from the data source and binds it to the thread so all subsequent repository
     * calls share it until {@link #endScope(DataSource)}. Unlike a transaction the
     * connection keeps its auto-commit setting, so each SQL statement commits
     * independently — a scope only saves pool round-trips, it does not provide
     * atomicity.
     *
     * <p>A scope begun while a transaction is active on the same data source returns
     * the transaction connection and binds nothing — the scope becomes a no-op and
     * {@link #endScope(DataSource)} has nothing to clean up. Nested scopes on the
     * same data source reuse the outer scope's connection. Starting a transaction
     * while a scope is active is rejected.</p>
     *
     * @param dataSource the data source the scope's connection is borrowed from
     * @return the shared scope connection, owned by the scope — do not close it
     *         directly, and do not use it after {@link #endScope(DataSource)}
     * @throws OrmException if the connection cannot be obtained
     */
    Connection beginScope(DataSource dataSource);

    /**
     * Ends the connection scope begun by {@link #beginScope(DataSource)}: unbinds
     * the scope connection and returns it to the pool. A no-op when the thread has
     * no active scope (e.g. a scope that joined an active transaction).
     *
     * @param dataSource the data source the scope was begun on
     */
    void endScope(DataSource dataSource);

    // ---- Global extension point --------------------------------------------

    /**
     * The globally installed transaction manager.  The built-in default is
     * {@link SimpleTransactionManager}.  A Spring Boot starter replaces this
     * with a wrapper around {@code PlatformTransactionManager}.
     */
    TransactionManager DEFAULT = new SimpleTransactionManager();

    /**
     * Returns the currently-installed manager.
     * Generated code always obtains transactions through this method.
     */
    static TransactionManager current() {
        return Holder.INSTANCE;
    }

    /**
     * Replaces the global transaction manager.
     * Called once at startup (e.g. from a Spring auto-configuration).
     */
    static void set(TransactionManager manager) {
        Holder.INSTANCE = manager;
    }

    // Internal holder to allow replacing the singleton.
    final class Holder {
        static TransactionManager INSTANCE = DEFAULT;

        private Holder() {
        }
    }
}