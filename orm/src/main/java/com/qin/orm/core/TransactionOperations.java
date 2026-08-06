package com.qin.orm.core;

import com.qin.orm.OrmException;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Programmatic transaction contract inherited by every repository.
 *
 * <p>User {@code @Dao} interfaces extend {@link BaseRepository}, which extends
 * this interface, so every generated repository exposes {@code beginTransaction/
 * commit/rollback/isTransactionActive} plus the {@link #execute} template. The
 * transaction lives on the current thread (backed by the global
 * {@link com.qin.orm.TransactionManager}); while it is active, all repositories
 * bound to the same {@code DataSource} share one connection and one transaction
 * boundary, so multi-repository work can be wrapped in a single transaction.</p>
 *
 * <p>Nested transactions are not supported: calling {@link #beginTransaction()}
 * while a transaction is already active on the thread throws an
 * {@link com.qin.orm.OrmException}.</p>
 */
public interface TransactionOperations {

    /**
     * Starts a new transaction on the current thread: acquires a connection from
     * the repository's data source, disables auto-commit, and binds it to the
     * thread so all subsequent repository calls join the transaction.
     *
     * <p>Returns the transaction-bound {@link Connection} so callers can apply
     * raw-JDBC control (isolation, savepoints, direct SQL) — and so the default
     * {@link #execute} can hand it to the callback. The connection is owned by the
     * transaction: do not close it directly, and do not use it after
     * {@link #commit()} or {@link #rollback()}.</p>
     *
     * @return the connection bound to the newly started transaction
     * @throws com.qin.orm.OrmException if a transaction is already active on this
     *                                  thread, or the connection cannot be obtained
     */
    Connection beginTransaction();

    /**
     * Commits the active transaction and releases its connection.
     *
     * @throws com.qin.orm.OrmException if no transaction is active, or the commit fails
     */
    void commit();

    /**
     * Rolls back the active transaction and releases its connection.
     *
     * @throws com.qin.orm.OrmException if no transaction is active, or the rollback fails
     */
    void rollback();

    /**
     * Returns whether a transaction is currently active on this thread.
     *
     * @return {@code true} when a transaction was begun and not yet committed/rolled back
     */
    boolean isTransactionActive();

    /**
     * Runs {@code callback} inside a transaction: begins a transaction, invokes the
     * callback with the transaction-bound {@link Connection}, then commits on
     * success. If the callback or the commit throws, the transaction is rolled back
     * and the exception propagated — an {@link SQLException} from the callback is
     * wrapped into {@link OrmException}; any {@link RuntimeException} or
     * {@link Error} propagates unchanged.
     *
     * <p>The {@code Connection} parameter gives callers the raw-JDBC control the ORM
     * deliberately does not abstract — isolation level, savepoints, read-only, query
     * timeouts or direct SQL that participates in the transaction — without exposing
     * connection ownership: {@code execute} manages the connection's lifecycle, and
     * the low-level get/release pair stays private in the generated implementation.</p>
     *
     * @param callback the transactional work, receiving the transaction-bound connection
     * @param <T>      the callback's return type
     * @return the callback's result, or {@code null} for side-effect-only bodies
     * @throws OrmException if the transaction cannot be begun, committed or rolled back,
     *                      or the callback throws SQLException
     */
    default <T> T execute(TransactionCallback<T> callback) {
        Connection conn = beginTransaction();
        try {
            T result = callback.doInTransaction(conn);
            commit();
            return result;
        } catch (SQLException e) {
            // Wrap JDBC failures from the callback, matching the ORM's
            // no-checked-exceptions contract.
            rollbackQuietly();
            throw new OrmException("Transaction failed", e);
        } catch (RuntimeException | Error ex) {
            // Roll back the partially-executed body; a failed commit has already
            // released the connection, so a quiet rollback prevents masking the
            // original exception with a secondary "no active transaction" error.
            rollbackQuietly();
            throw ex;
        }
    }

    /**
     * Rolls back the active transaction, swallowing a "no transaction" failure.
     * Used by {@link #execute} so a commit failure does not hide the primary error.
     */
    private void rollbackQuietly() {
        try {
            rollback();
        } catch (RuntimeException ignored) {
            // Best effort: the commit failure path already cleared the thread state.
        }
    }
}
