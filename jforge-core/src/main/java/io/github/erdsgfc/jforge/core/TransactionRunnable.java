package io.github.erdsgfc.jforge.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Void-returning callback executed inside a programmatic transaction via
 * {@link TransactionOperations#run(TransactionRunnable)} — the counterpart of
 * {@link TransactionCallback} for side-effect-only bodies that need no {@code
 * return null}.
 *
 * <p>Like {@link TransactionCallback}, the callback receives the transaction-bound
 * {@link Connection} for raw-JDBC control, and a {@link SQLException} is wrapped
 * into {@link io.github.erdsgfc.jforge.OrmException} with a rollback.</p>
 */
@FunctionalInterface
public interface TransactionRunnable {

    /**
     * Runs the transactional work with the transaction-bound connection.
     *
     * @param conn the connection bound to the active transaction
     * @throws SQLException if a JDBC operation fails
     */
    void doInTransaction(Connection conn) throws SQLException;
}