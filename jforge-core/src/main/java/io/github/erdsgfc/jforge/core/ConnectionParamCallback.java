package io.github.erdsgfc.jforge.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Callback executed with a framework-provided {@link Connection} together with
 * an externally supplied parameter via
 * {@link TransactionOperations#execute(Object, ConnectionParamCallback)}.
 *
 * <p>Like {@link ConnectionCallback}, but the callback additionally receives a
 * user-supplied parameter (of any type), so external state can be passed into
 * the work body without capturing it in a closure.</p>
 *
 * @param <T> the return type of the work body
 * @param <P> the type of the externally supplied parameter
 */
@FunctionalInterface
public interface ConnectionParamCallback<T, P> {

    /**
     * Runs the work with the shared connection and the supplied parameter. May
     * throw {@link SQLException} — the caller wraps it into
     * {@link io.github.erdsgfc.jforge.JForgeException} and rolls back.
     *
     * @param conn  the connection bound to the active transaction, or shared by
     *              the active connection scope
     * @param param the externally supplied parameter
     * @return the value to return from {@link TransactionOperations#execute}, or
     *         {@code null} when the body is run for side effects only
     * @throws SQLException if a JDBC operation fails
     */
    T doInConnection(Connection conn, P param) throws SQLException;
}
