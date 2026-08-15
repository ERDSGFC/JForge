package io.github.erdsgfc.jforge.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Callback executed with a framework-provided {@link Connection} via
 * {@link TransactionOperations#execute(ConnectionCallback)} (inside a
 * programmatic transaction) — or on a single shared connection without a
 * transaction via
 * {@link TransactionOperations#executeWithoutTransaction(ConnectionCallback)}.
 * The functional shape is identical in both contexts, so one lambda works
 * unchanged for transactional and scope-based work.
 *
 * <p>The callback receives the transaction-bound (or scope-shared)
 * {@link Connection}, so it can apply raw-JDBC control the ORM deliberately does
 * not abstract — isolation level, savepoints, read-only, query timeouts or
 * direct SQL that participates in the transaction. It must not close the
 * connection; {@code execute} manages its lifecycle.</p>
 *
 * @param <T> the return type of the work body
 */
@FunctionalInterface
public interface ConnectionCallback<T> {

    /**
     * Runs the work with the shared connection. May throw {@link SQLException} —
     * {@link TransactionOperations#execute} wraps it into
     * {@link io.github.erdsgfc.jforge.JForgeException} and rolls back; any
     * unchecked exception also triggers a rollback and propagates unchanged.
     *
     * @param conn the connection bound to the active transaction, or shared by
     *             the active connection scope
     * @return the value to return from {@link TransactionOperations#execute}, or
     *         {@code null} when the body is run for side effects only
     * @throws SQLException if a JDBC operation fails
     */
    T doInConnection(Connection conn) throws SQLException;
}
