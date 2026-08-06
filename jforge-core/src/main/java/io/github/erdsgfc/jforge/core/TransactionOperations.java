package io.github.erdsgfc.jforge.core;

import io.github.erdsgfc.jforge.OrmException;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Programmatic transaction contract inherited by every repository.
 *
 * <p>User {@code @Dao} interfaces extend {@link BaseRepository}, which extends
 * this interface, so every generated repository exposes {@code beginTransaction/
 * commit/rollback/isTransactionActive} plus the {@link #execute} template. The
 * transaction lives on the current thread (backed by the global
 * {@link io.github.erdsgfc.jforge.TransactionManager}); while it is active, all repositories
 * bound to the same {@code DataSource} share one connection and one transaction
 * boundary, so multi-repository work can be wrapped in a single transaction.</p>
 *
 * <p>Nested ORM-level transactions are not supported: calling {@link #beginTransaction()}
 * while a transaction begun via this API is still active on the thread throws an
 * {@link io.github.erdsgfc.jforge.OrmException}. With the {@code jforge-spring-boot-starter}
 * installed, calling it inside an outer Spring transaction (e.g. a
 * {@code @Transactional} service method) instead joins that transaction
 * ({@code PROPAGATION_REQUIRED}).</p>
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
     * <p>With the {@code jforge-spring-boot-starter} installed, calling this inside an
     * already-active Spring transaction joins it ({@code PROPAGATION_REQUIRED})
     * instead of throwing; the throw is reserved for a second ORM-level begin
     * without an intervening commit/rollback.</p>
     *
     * @return the connection bound to the newly started transaction
     * @throws io.github.erdsgfc.jforge.OrmException if another ORM-level transaction is already
     *                                  active on this thread, or the connection
     *                                  cannot be obtained
     */
    Connection beginTransaction();

    /**
     * Commits the active transaction and releases its connection.
     *
     * @throws io.github.erdsgfc.jforge.OrmException if no transaction is active, or the commit fails
     */
    void commit();

    /**
     * Rolls back the active transaction and releases its connection.
     *
     * @throws io.github.erdsgfc.jforge.OrmException if no transaction is active, or the rollback fails
     */
    void rollback();

    /**
     * Returns whether a transaction is currently active on this thread. With the
     * built-in {@code SimpleTransactionManager} this is true only for a transaction
     * begun via {@link #beginTransaction()}; with the {@code jforge-spring-boot-starter}
     * installed it is also true while the thread participates in an outer Spring
     * transaction (e.g. a {@code @Transactional} service method), detected through
     * Spring's {@code TransactionSynchronizationManager}.
     *
     * @return {@code true} when a transaction is active on this thread
     */
    boolean isTransactionActive();

    /**
     * Marks the active transaction for rollback without throwing: the transaction is
     * rolled back when it completes, even if {@link #execute} returns normally. Used
     * to abort on a business rule while still returning a result from the callback.
     *
     * @throws io.github.erdsgfc.jforge.OrmException if no transaction is active
     */
    void markRollbackOnly();

    /**
     * Returns whether the active transaction has been marked for rollback via
     * {@link #markRollbackOnly()}. With the Spring starter installed this is also
     * {@code true} when the outer Spring transaction the ORM joined has been marked
     * rollback-only.
     *
     * @return {@code true} when the active transaction is marked for rollback
     */
    boolean isRollbackOnly();

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
        // Delegate to the parameterised overload with a null Void parameter, so the
        // commit/rollback logic lives in exactly one place.
        return execute((Void) null, (conn, ignored) -> callback.doInTransaction(conn));
    }

    /**
     * Runs {@code callback} inside a transaction, passing it the externally supplied
     * {@code param} alongside the transaction-bound {@link Connection}. Behaviour is
     * identical to {@link #execute(TransactionCallback)}: commit on success, rollback
     * and propagate on any exception (an {@link SQLException} from the callback is
     * wrapped into {@link OrmException}). The parameter must be a typed value so the
     * compiler can infer {@code P}; a literal {@code null} requires an explicit type
     * (a cast or an explicitly-typed lambda parameter). To run a transaction without
     * a parameter, use {@link #execute(TransactionCallback)}.
     *
     * @param param    the externally supplied parameter, forwarded to the callback
     * @param callback the transactional work, receiving the connection and the parameter
     * @param <T>      the callback's return type
     * @param <P>      the parameter type
     * @return the callback's result, or {@code null} for side-effect-only bodies
     * @throws OrmException if the transaction cannot be begun, committed or rolled back,
     *                      or the callback throws SQLException
     */
    default <T, P> T execute(P param, TransactionParamCallback<T, P> callback) {
        Connection conn = beginTransaction();
        try {
            T result = callback.doInTransaction(conn, param);
            if (isRollbackOnly()) {
                // The callback (or an outer Spring transaction) marked the transaction
                // rollback-only to abort without throwing: roll back, but still return
                // the callback's result normally.
                rollback();
            } else {
                commit();
            }
            return result;
        } catch (SQLException e) {
            // Wrap JDBC failures from the callback, matching the ORM's
            // no-checked-exceptions contract; keep the JDBC error message as context.
            rollbackQuietly();
            throw new OrmException(
                    "Transaction failed" + (e.getMessage() != null ? ": " + e.getMessage() : ""), e);
        } catch (RuntimeException | Error ex) {
            // Roll back the partially-executed body; a failed commit has already
            // released the connection, so a quiet rollback prevents masking the
            // original exception with a secondary "no active transaction" error.
            rollbackQuietly();
            throw ex;
        }
    }

    /**
     * Runs {@code runnable} inside a transaction without a return value — the
     * void counterpart of {@link #execute(TransactionCallback)}, for side-effect-only
     * bodies that need no {@code return null}. Behaviour is identical: commit on
     * success, rollback and propagate on any exception (an {@link SQLException} from
     * the body is wrapped into {@link OrmException}).
     *
     * @param runnable the transactional work
     * @throws OrmException if the transaction cannot be begun, committed or rolled back,
     *                      or the body throws SQLException
     */
    default void run(TransactionRunnable runnable) {
        execute(conn -> {
            runnable.doInTransaction(conn);
            return null;
        });
    }

    /**
     * Runs {@code runnable} inside a transaction, passing it the externally supplied
     * {@code param} alongside the transaction-bound {@link Connection}, without a
     * return value — the void counterpart of
     * {@link #execute(Object, TransactionParamCallback)}.
     *
     * @param param    the externally supplied parameter, forwarded to the body
     * @param runnable the transactional work, receiving the connection and the parameter
     * @param <P>      the parameter type
     * @throws OrmException if the transaction cannot be begun, committed or rolled back,
     *                      or the body throws SQLException
     */
    default <P> void run(P param, TransactionParamRunnable<P> runnable) {
        execute(param, (conn, p) -> {
            runnable.doInTransaction(conn, p);
            return null;
        });
    }

    // ---- Connection scope (no transaction) ----------------------------------

    /**
     * Begins a connection scope on the current thread: borrows a single connection
     * from the repository's data source and binds it to the thread so all
     * repository calls share it until {@link #endConnectionScope()}. The generated
     * implementation delegates to {@code TransactionManager.beginScope(dataSource)}.
     *
     * <p>Unlike {@link #beginTransaction()} the connection keeps auto-commit
     * enabled: a scope only saves pool round-trips, it does not provide atomicity.
     * A scope begun inside an active transaction (or Spring {@code @Transactional})
     * reuses the transaction connection and needs no cleanup.</p>
     *
     * @return the shared scope connection, owned by the scope — do not close it
     *         directly, and do not use it after {@link #endConnectionScope()}
     * @throws io.github.erdsgfc.jforge.OrmException if the connection cannot be obtained
     */
    Connection beginConnectionScope();

    /**
     * Ends the connection scope begun by {@link #beginConnectionScope()}: returns
     * the scope connection to the pool. A no-op when the thread has no active
     * scope (e.g. a scope that joined an active transaction).
     */
    void endConnectionScope();

    /**
     * Runs {@code callback} on a single shared connection but without a
     * transaction: the ORM borrows one connection from the pool, the callback's
     * repository calls reuse it, and the connection is returned to the pool when
     * the callback finishes — success or failure. The connection keeps auto-commit
     * enabled, so every SQL statement commits independently: unlike
     * {@link #execute(TransactionCallback)} there is <em>no atomicity</em> —
     * statements executed before the callback throws stay committed.
     *
     * <p>Use this for multi-statement work that only wants to avoid a pool
     * round-trip per statement and needs no rollback semantics; use
     * {@link #execute(TransactionCallback)} when the statements must commit or
     * roll back together. A transaction cannot be begun inside the scope
     * (it throws {@link io.github.erdsgfc.jforge.OrmException}).</p>
     *
     * @param callback the work to run on the shared connection
     * @param <T>      the callback's return type
     * @return the callback's result, or {@code null} for side-effect-only bodies
     * @throws io.github.erdsgfc.jforge.OrmException if the connection cannot be obtained,
     *                                  or the callback throws {@link SQLException}
     */
    default <T> T executeWithoutTransaction(ConnectionScopeCallback<T> callback) {
        Connection conn = beginConnectionScope();
        try {
            return callback.doInScope(conn);
        } catch (SQLException e) {
            // Match the execute() contract: JDBC failures from the callback are
            // wrapped into OrmException. The scope connection is still returned to
            // the pool by the finally block.
            throw new OrmException(
                    "Connection scope failed" + (e.getMessage() != null ? ": " + e.getMessage() : ""), e);
        } finally {
            endConnectionScope();
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
