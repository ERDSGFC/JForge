package io.github.erdsgfc.jforge.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Callback executed inside a programmatic transaction via
 * {@link TransactionOperations#execute(TransactionCallback)}.
 *
 * <p>The callback receives the transaction-bound {@link Connection}, so it can apply
 * raw-JDBC control the ORM deliberately does not abstract — isolation level,
 * savepoints, read-only, query timeouts or direct SQL that participates in the
 * transaction. It must not close the connection; {@code execute} manages its
 * lifecycle.</p>
 *
 * @param <T> the return type of the transaction body
 */
@FunctionalInterface
public interface TransactionCallback<T> {

    /**
     * Runs the transactional work with the transaction-bound connection. May throw
     * {@link SQLException} — {@link TransactionOperations#execute} wraps it into
     * {@link io.github.erdsgfc.jforge.OrmException} and rolls back; any unchecked exception also
     * triggers a rollback and propagates unchanged.
     *
     * @param conn the connection bound to the active transaction
     * @return the value to return from {@link TransactionOperations#execute}, or
     *         {@code null} when the body is run for side effects only
     * @throws SQLException if a JDBC operation fails
     */
    T doInTransaction(Connection conn) throws SQLException;
}