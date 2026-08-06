package io.github.erdsgfc.jforge.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Void-returning callback executed inside a programmatic transaction together with
 * an externally supplied parameter via
 * {@link TransactionOperations#run(Object, TransactionParamRunnable)} — the
 * counterpart of {@link TransactionParamCallback} for side-effect-only bodies that
 * need no {@code return null}.
 *
 * <p>The callback receives the transaction-bound {@link Connection} and the
 * user-supplied parameter; a {@link SQLException} is wrapped into
 * {@link io.github.erdsgfc.jforge.OrmException} with a rollback.</p>
 *
 * @param <P> the type of the externally supplied parameter
 */
@FunctionalInterface
public interface TransactionParamRunnable<P> {

    /**
     * Runs the transactional work with the transaction-bound connection and the
     * supplied parameter.
     *
     * @param conn  the connection bound to the active transaction
     * @param param the externally supplied parameter
     * @throws SQLException if a JDBC operation fails
     */
    void doInTransaction(Connection conn, P param) throws SQLException;
}