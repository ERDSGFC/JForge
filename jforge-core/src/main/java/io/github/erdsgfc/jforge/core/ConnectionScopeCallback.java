package io.github.erdsgfc.jforge.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Callback executed on a single shared connection without a transaction via
 * {@link TransactionOperations#executeWithoutTransaction(ConnectionScopeCallback)}.
 *
 * <p>The callback receives the scope's shared {@link Connection} — the same
 * connection every repository call inside the callback reuses — so it can apply
 * raw JDBC control that participates in the scope. It must not close the
 * connection; {@code executeWithoutTransaction} manages its lifecycle.</p>
 *
 * <p>Unlike {@link TransactionCallback} there is no rollback semantics: the
 * connection keeps auto-commit enabled, so statements executed before the
 * callback throws stay committed.</p>
 *
 * @param <T> the return type of the scope body
 */
@FunctionalInterface
public interface ConnectionScopeCallback<T> {

    /**
     * Runs the scope body on the shared connection. May throw {@link SQLException}
     * — {@link TransactionOperations#executeWithoutTransaction} wraps it into
     * {@link io.github.erdsgfc.jforge.OrmException}; any unchecked exception
     * propagates unchanged. In both cases the scope connection is returned to the
     * pool, and statements already executed stay committed (no atomicity).
     *
     * @param conn the shared connection owned by the scope
     * @return the value to return from
     *         {@link TransactionOperations#executeWithoutTransaction}, or
     *         {@code null} when the body is run for side effects only
     * @throws SQLException if a JDBC operation fails
     */
    T doInScope(Connection conn) throws SQLException;
}