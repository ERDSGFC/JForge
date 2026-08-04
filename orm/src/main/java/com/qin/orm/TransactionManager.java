package com.qin.orm;

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