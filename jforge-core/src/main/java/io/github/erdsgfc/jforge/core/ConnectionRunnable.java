package io.github.erdsgfc.jforge.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Void-returning callback executed with a framework-provided {@link Connection}
 * via {@link TransactionOperations#run(ConnectionRunnable)} (inside a
 * programmatic transaction) — or on a single shared connection without a
 * transaction via
 * {@link TransactionOperations#runWithoutTransaction(ConnectionRunnable)}.
 * The counterpart of {@link ConnectionCallback} for side-effect-only bodies
 * that need no {@code return null}.
 *
 * <p>Like {@link ConnectionCallback}, the callback receives the transaction-bound
 * (or scope-shared) {@link Connection} for raw-JDBC control, and a
 * {@link SQLException} is wrapped into {@link io.github.erdsgfc.jforge.JForgeException}
 * with a rollback (or, for a scope, without one — statements already executed
 * stay committed).</p>
 */
@FunctionalInterface
public interface ConnectionRunnable {

    /**
     * Runs the work with the shared connection.
     *
     * @param conn the connection bound to the active transaction, or shared by
     *             the active connection scope
     * @throws SQLException if a JDBC operation fails
     */
    void doInConnection(Connection conn) throws SQLException;
}
