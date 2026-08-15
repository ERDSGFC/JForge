package io.github.erdsgfc.jforge;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Pluggable transaction manager — the single extension point for replacing the
 * built-in thread-local transaction with Spring's {@code PlatformTransactionManager}.
 *
 * <p>The manager is passed <em>by instance</em>: {@link JForge} holds it as a
 * {@code private final} field and hands it to every repository it creates, so the
 * generated implementations store it in their own {@code final} fields (JIT-
 * friendly, no static lookup). A Spring Boot starter supplies its Spring-aware
 * implementation the same way — as the bean injected into generated repositories.
 * There is no global singleton and no way to swap the manager after construction.</p>
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
     * @throws JForgeException if no transaction is active on this thread
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
     * @throws JForgeException if the connection cannot be obtained
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
}