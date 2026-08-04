package com.qin.orm.core;

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
     * @throws com.qin.orm.OrmException if a transaction is already active on this
     *                                 thread, or the connection cannot be obtained
     */
    void beginTransaction();

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
     * Runs {@code callback} inside a transaction: begins a transaction, executes
     * the body, then commits on success. If the body (or the commit itself) throws
     * any {@link RuntimeException} or {@link Error}, the transaction is rolled back
     * and the original exception rethrown — the caller never has to remember to
     * roll back.
     *
     * @param callback the transactional work, receiving no status object
     * @param <T>      the callback's return type
     * @return the callback's result, or {@code null} for side-effect-only bodies
     * @throws com.qin.orm.OrmException if the transaction cannot be begun, committed,
     *                                  or rolled back; any exception thrown by the
     *                                  callback propagates unchanged after rollback
     */
    default <T> T execute(TransactionCallback<T> callback) {
        beginTransaction();
        try {
            T result = callback.doInTransaction();
            commit();
            return result;
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
