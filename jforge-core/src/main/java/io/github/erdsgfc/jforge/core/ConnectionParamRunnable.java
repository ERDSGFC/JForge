package io.github.erdsgfc.jforge.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Void-returning callback executed with a framework-provided {@link Connection}
 * together with an externally supplied parameter via
 * {@link TransactionOperations#run(Object, ConnectionParamRunnable)} — or on a
 * single shared connection without a transaction via
 * {@link TransactionOperations#runWithoutTransaction(Object, ConnectionParamRunnable)}.
 * The counterpart of {@link ConnectionParamCallback} for side-effect-only bodies
 * that need no {@code return null}.
 *
 * <p>The callback receives the transaction-bound (or scope-shared)
 * {@link Connection} and the user-supplied parameter; a {@link SQLException} is
 * wrapped into {@link io.github.erdsgfc.jforge.JForgeException} with a rollback
 * (or, for a scope, without one — statements already executed stay committed).</p>
 *
 * @param <P> the type of the externally supplied parameter
 */
@FunctionalInterface
public interface ConnectionParamRunnable<P> {

    /**
     * Runs the work with the shared connection and the supplied parameter.
     *
     * @param conn  the connection bound to the active transaction, or shared by
     *              the active connection scope
     * @param param the externally supplied parameter
     * @throws SQLException if a JDBC operation fails
     */
    void doInConnection(Connection conn, P param) throws SQLException;
}
