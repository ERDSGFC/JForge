package com.qin.orm.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Callback executed inside a programmatic transaction together with an externally
 * supplied parameter via
 * {@link TransactionOperations#execute(Object, TransactionParamCallback)}.
 *
 * <p>Like {@link TransactionCallback}, but the callback additionally receives a
 * user-supplied parameter (of any type), so external state can be passed into the
 * transaction body without capturing it in a closure.</p>
 *
 * @param <T> the return type of the transaction body
 * @param <P> the type of the externally supplied parameter
 */
@FunctionalInterface
public interface TransactionParamCallback<T, P> {

    /**
     * Runs the transactional work with the transaction-bound connection and the
     * supplied parameter. May throw {@link SQLException} — the caller wraps it into
     * {@link com.qin.orm.OrmException} and rolls back.
     *
     * @param conn  the connection bound to the active transaction
     * @param param the externally supplied parameter
     * @return the value to return from {@link TransactionOperations#execute}, or
     *         {@code null} when the body is run for side effects only
     * @throws SQLException if a JDBC operation fails
     */
    T doInTransaction(Connection conn, P param) throws SQLException;
}
