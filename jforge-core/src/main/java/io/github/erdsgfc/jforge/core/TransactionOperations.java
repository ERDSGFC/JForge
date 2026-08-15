package io.github.erdsgfc.jforge.core;

import io.github.erdsgfc.jforge.JForgeException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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
 * {@link io.github.erdsgfc.jforge.JForgeException}. With the {@code jforge-spring-boot-starter}
 * installed, calling it inside an outer Spring transaction (e.g. a
 * {@code @Transactional} service method) instead joins that transaction
 * ({@code PROPAGATION_REQUIRED}).</p>
 *
 * <p>Both the transactional family ({@code execute}/{@code run}) and the
 * connection-scope family ({@code executeWithoutTransaction}/
 * {@code runWithoutTransaction}) cover the full shape matrix — with or without an
 * external parameter, with or without a return value, and with or without the
 * {@link Connection} exposed to the callback. All variants delegate to one
 * private core each ({@link #inTransaction} / {@link #inScope}), so the
 * commit/rollback (or borrow/release) logic lives in exactly one place.</p>
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
     * @throws io.github.erdsgfc.jforge.JForgeException if another ORM-level transaction is already
     *                                  active on this thread, or the connection
     *                                  cannot be obtained
     */
    Connection beginTransaction();

    /**
     * Commits the active transaction and releases its connection.
     *
     * @throws io.github.erdsgfc.jforge.JForgeException if no transaction is active, or the commit fails
     */
    void commit();

    /**
     * Rolls back the active transaction and releases its connection.
     *
     * @throws io.github.erdsgfc.jforge.JForgeException if no transaction is active, or the rollback fails
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
     * @throws io.github.erdsgfc.jforge.JForgeException if no transaction is active
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

    // ---- 事务族:execute/run ----------------------------------------------

    /**
     * Runs {@code callback} inside a transaction: begins a transaction, invokes the
     * callback with the transaction-bound {@link Connection}, then commits on
     * success. If the callback or the commit throws, the transaction is rolled back
     * and the exception propagated — an {@link SQLException} from the callback is
     * wrapped into {@link JForgeException}; any {@link RuntimeException} or
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
     * @throws JForgeException if the transaction cannot be begun, committed or rolled back,
     *                      or the callback throws SQLException
     */
    default <T> T execute(ConnectionCallback<T> callback) {
        return inTransaction(callback);
    }

    /**
     * Runs {@code callback} inside a transaction, passing it the externally supplied
     * {@code param} alongside the transaction-bound {@link Connection}. Behaviour is
     * identical to {@link #execute(ConnectionCallback)}: commit on success, rollback
     * and propagate on any exception (an {@link SQLException} from the callback is
     * wrapped into {@link JForgeException}). The parameter must be a typed value so the
     * compiler can infer {@code P}; a literal {@code null} requires an explicit type
     * (a cast or an explicitly-typed lambda parameter). To run a transaction without
     * a parameter, use {@link #execute(ConnectionCallback)}.
     *
     * @param param    the externally supplied parameter, forwarded to the callback
     * @param callback the transactional work, receiving the connection and the parameter
     * @param <T>      the callback's return type
     * @param <P>      the parameter type
     * @return the callback's result, or {@code null} for side-effect-only bodies
     * @throws JForgeException if the transaction cannot be begun, committed or rolled back,
     *                      or the callback throws SQLException
     */
    default <T, P> T execute(P param, ConnectionParamCallback<T, P> callback) {
        return inTransaction(conn -> callback.doInConnection(conn, param));
    }

    /**
     * Runs {@code supplier} inside a transaction without exposing the connection —
     * for bodies that call only repository methods (which join the transaction
     * implicitly) and never need raw-JDBC control. No checked-exception handling is
     * required: without the {@code Connection} there is no {@link SQLException}
     * path; repository failures surface as the unchecked {@link JForgeException}.
     * Commit/rollback semantics are identical to
     * {@link #execute(ConnectionCallback)}.
     *
     * @param supplier the transactional work, returning a value
     * @param <T>      the result type
     * @return the supplier's result
     * @throws JForgeException if the transaction cannot be begun, committed or rolled back,
     *                      or a repository call fails
     */
    default <T> T execute(Supplier<T> supplier) {
        return inTransaction(ignored -> supplier.get());
    }

    /**
     * Runs {@code function} inside a transaction, passing it the externally supplied
     * {@code param} but not the connection — the parameterised counterpart of
     * {@link #execute(Supplier)} for bodies that call only repository methods.
     * No checked-exception handling is required (no {@link SQLException} path);
     * commit/rollback semantics are identical to
     * {@link #execute(Object, ConnectionParamCallback)}.
     *
     * @param param    the externally supplied parameter, forwarded to the body
     * @param function the transactional work, receiving the parameter
     * @param <T>      the result type
     * @param <P>      the parameter type
     * @return the function's result
     * @throws JForgeException if the transaction cannot be begun, committed or rolled back,
     *                      or a repository call fails
     */
    default <T, P> T execute(P param, Function<P, T> function) {
        return inTransaction(ignored -> function.apply(param));
    }

    /**
     * Runs {@code runnable} inside a transaction without a return value — the
     * void counterpart of {@link #execute(ConnectionCallback)}, for side-effect-only
     * bodies that need no {@code return null}. Behaviour is identical: commit on
     * success, rollback and propagate on any exception (an {@link SQLException} from
     * the body is wrapped into {@link JForgeException}).
     *
     * @param runnable the transactional work
     * @throws JForgeException if the transaction cannot be begun, committed or rolled back,
     *                      or the body throws SQLException
     */
    default void run(ConnectionRunnable runnable) {
        execute(conn -> {
            runnable.doInConnection(conn);
            return null;
        });
    }

    /**
     * Runs {@code runnable} inside a transaction, passing it the externally supplied
     * {@code param} alongside the transaction-bound {@link Connection}, without a
     * return value — the void counterpart of
     * {@link #execute(Object, ConnectionParamCallback)}.
     *
     * @param param    the externally supplied parameter, forwarded to the body
     * @param runnable the transactional work, receiving the connection and the parameter
     * @param <P>      the parameter type
     * @throws JForgeException if the transaction cannot be begun, committed or rolled back,
     *                      or the body throws SQLException
     */
    default <P> void run(P param, ConnectionParamRunnable<P> runnable) {
        execute(param, (conn, p) -> {
            runnable.doInConnection(conn, p);
            return null;
        });
    }

    /**
     * Runs {@code runnable} inside a transaction without a return value and without
     * exposing the connection — the void counterpart of {@link #execute(Supplier)}
     * for side-effect-only bodies that call only repository methods. No
     * checked-exception handling is required (no {@link SQLException} path);
     * commit/rollback semantics are identical to {@link #run(ConnectionRunnable)}.
     *
     * @param runnable the transactional work
     * @throws JForgeException if the transaction cannot be begun, committed or rolled back,
     *                      or a repository call fails
     */
    default void run(Runnable runnable) {
        execute(ignored -> {
            runnable.run();
            return null;
        });
    }

    /**
     * Runs {@code consumer} inside a transaction, passing it the externally supplied
     * {@code param} but not the connection, without a return value — the void
     * counterpart of {@link #execute(Object, Function)}. No checked-exception
     * handling is required (no {@link SQLException} path); commit/rollback
     * semantics are identical to {@link #run(Object, ConnectionParamRunnable)}.
     *
     * @param param    the externally supplied parameter, forwarded to the body
     * @param consumer the transactional work, receiving the parameter
     * @param <P>      the parameter type
     * @throws JForgeException if the transaction cannot be begun, committed or rolled back,
     *                      or a repository call fails
     */
    default <P> void run(P param, Consumer<P> consumer) {
        execute(param, (ignored, p) -> {
            consumer.accept(p);
            return null;
        });
    }

    // ---- 作用域族:executeWithoutTransaction/runWithoutTransaction -----------

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
     * @throws io.github.erdsgfc.jforge.JForgeException if the connection cannot be obtained
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
     * {@link #execute(ConnectionCallback)} there is <em>no atomicity</em> —
     * statements executed before the callback throws stay committed.
     *
     * <p>The callback type is the shared {@link ConnectionCallback} — the same
     * functional shape as the transactional {@link #execute(ConnectionCallback)},
     * so a lambda works unchanged in both contexts. Use this for multi-statement
     * work that only wants to avoid a pool round-trip per statement and needs no
     * rollback semantics; use {@link #execute(ConnectionCallback)} when the
     * statements must commit or roll back together. A transaction cannot be begun
     * inside the scope (it throws {@link io.github.erdsgfc.jforge.JForgeException}).</p>
     *
     * @param callback the work to run on the shared connection
     * @param <T>      the callback's return type
     * @return the callback's result, or {@code null} for side-effect-only bodies
     * @throws io.github.erdsgfc.jforge.JForgeException if the connection cannot be obtained,
     *                                  or the callback throws {@link SQLException}
     */
    default <T> T executeWithoutTransaction(ConnectionCallback<T> callback) {
        return inScope(callback);
    }

    /**
     * Runs {@code callback} on a single shared connection without a transaction,
     * passing it the externally supplied {@code param} alongside the shared
     * {@link Connection} — the parameterised counterpart of
     * {@link #executeWithoutTransaction(ConnectionCallback)}. Behaviour is
     * identical: one borrowed connection, kept auto-commit (no atomicity), returned
     * to the pool in a {@code finally}; a {@link SQLException} from the callback is
     * wrapped into {@link JForgeException}.
     *
     * @param param    the externally supplied parameter, forwarded to the callback
     * @param callback the work to run on the shared connection
     * @param <T>      the callback's return type
     * @param <P>      the parameter type
     * @return the callback's result, or {@code null} for side-effect-only bodies
     * @throws io.github.erdsgfc.jforge.JForgeException if the connection cannot be obtained,
     *                                  or the callback throws {@link SQLException}
     */
    default <T, P> T executeWithoutTransaction(P param, ConnectionParamCallback<T, P> callback) {
        return inScope(conn -> callback.doInConnection(conn, param));
    }

    /**
     * Runs {@code supplier} on a single shared connection without a transaction,
     * without exposing the connection — for bodies that call only repository
     * methods (which share the scope connection implicitly) and never need
     * raw-JDBC control. No checked-exception handling is required (no
     * {@link SQLException} path); borrow/release semantics are identical to
     * {@link #executeWithoutTransaction(ConnectionCallback)}.
     *
     * @param supplier the work to run on the shared connection, returning a value
     * @param <T>      the result type
     * @return the supplier's result
     * @throws io.github.erdsgfc.jforge.JForgeException if the connection cannot be obtained,
     *                                  or a repository call fails
     */
    default <T> T executeWithoutTransaction(Supplier<T> supplier) {
        return inScope(ignored -> supplier.get());
    }

    /**
     * Runs {@code function} on a single shared connection without a transaction,
     * passing it the externally supplied {@code param} but not the connection —
     * the parameterised counterpart of
     * {@link #executeWithoutTransaction(Supplier)}. No checked-exception handling
     * is required (no {@link SQLException} path); borrow/release semantics are
     * identical to
     * {@link #executeWithoutTransaction(Object, ConnectionParamCallback)}.
     *
     * @param param    the externally supplied parameter, forwarded to the body
     * @param function the work to run on the shared connection, receiving the parameter
     * @param <T>      the result type
     * @param <P>      the parameter type
     * @return the function's result
     * @throws io.github.erdsgfc.jforge.JForgeException if the connection cannot be obtained,
     *                                  or a repository call fails
     */
    default <T, P> T executeWithoutTransaction(P param, Function<P, T> function) {
        return inScope(ignored -> function.apply(param));
    }

    /**
     * Runs {@code runnable} on a single shared connection without a transaction —
     * the void counterpart of
     * {@link #executeWithoutTransaction(ConnectionCallback)}, for
     * side-effect-only bodies that need no {@code return null}. Behaviour is
     * identical: one borrowed connection shared by every repository call, kept
     * auto-commit (no atomicity), returned to the pool in a {@code finally} (a
     * {@link SQLException} from the body is wrapped into
     * {@link io.github.erdsgfc.jforge.JForgeException}).
     *
     * @param runnable the work to run on the shared connection
     * @throws io.github.erdsgfc.jforge.JForgeException if the connection cannot be obtained,
     *                                  or the body throws {@link SQLException}
     */
    default void runWithoutTransaction(ConnectionRunnable runnable) {
        executeWithoutTransaction(conn -> {
            runnable.doInConnection(conn);
            return null;
        });
    }

    /**
     * Runs {@code runnable} on a single shared connection without a transaction,
     * passing it the externally supplied {@code param} alongside the shared
     * {@link Connection} — the parameterised counterpart of
     * {@link #runWithoutTransaction(ConnectionRunnable)}. Behaviour is
     * identical: one borrowed connection, kept auto-commit (no atomicity),
     * returned to the pool in a {@code finally}; a {@link SQLException} from the
     * body is wrapped into {@link io.github.erdsgfc.jforge.JForgeException}.
     *
     * @param param    the externally supplied parameter, forwarded to the body
     * @param runnable the work to run on the shared connection
     * @param <P>      the parameter type
     * @throws io.github.erdsgfc.jforge.JForgeException if the connection cannot be obtained,
     *                                  or the body throws {@link SQLException}
     */
    default <P> void runWithoutTransaction(P param, ConnectionParamRunnable<P> runnable) {
        executeWithoutTransaction(conn -> {
            runnable.doInConnection(conn, param);
            return null;
        });
    }

    /**
     * Runs {@code runnable} on a single shared connection without a transaction —
     * the no-parameter, no-connection counterpart of
     * {@link #runWithoutTransaction(ConnectionRunnable)} for bodies that
     * call only repository methods and never touch the connection directly. The
     * body needs no checked-exception handling: without the {@code Connection}
     * there is no raw-JDBC path, so only the unchecked
     * {@link io.github.erdsgfc.jforge.JForgeException} from repository calls can
     * escape. Behaviour is otherwise identical: one borrowed connection shared by
     * every repository call, kept auto-commit (no atomicity), returned to the
     * pool in a {@code finally}.
     *
     * @param runnable the work to run on the shared connection
     * @throws io.github.erdsgfc.jforge.JForgeException if the connection cannot be obtained,
     *                                  or a repository call fails
     */
    default void runWithoutTransaction(Runnable runnable) {
        executeWithoutTransaction(ignored -> {
            runnable.run();
            return null;
        });
    }

    /**
     * Runs {@code consumer} on a single shared connection without a transaction,
     * passing it the externally supplied {@code param} but not the connection,
     * without a return value — the parameterised, no-connection counterpart of
     * {@link #runWithoutTransaction(ConnectionRunnable)}. No checked-exception
     * handling is required (no {@link SQLException} path); borrow/release
     * semantics are identical to
     * {@link #runWithoutTransaction(Object, ConnectionParamRunnable)}.
     *
     * @param param    the externally supplied parameter, forwarded to the body
     * @param consumer the work to run on the shared connection, receiving the parameter
     * @param <P>      the parameter type
     * @throws io.github.erdsgfc.jforge.JForgeException if the connection cannot be obtained,
     *                                  or a repository call fails
     */
    default <P> void runWithoutTransaction(P param, Consumer<P> consumer) {
        executeWithoutTransaction(ignored -> {
            consumer.accept(param);
            return null;
        });
    }

    // ---- 私有核心:每个族只有一处生命周期逻辑 --------------------------------

    /**
     * Shared transaction lifecycle, the single implementation of the commit/
     * rollback semantics behind every {@code execute}/{@code run} variant: begins
     * a transaction, runs the body, then commits — or rolls back when the body
     * (or an outer Spring transaction) marked the transaction rollback-only, or
     * when the body or the commit throws. An {@link SQLException} from the body
     * is wrapped into {@link JForgeException}; a failed commit is followed by a
     * quiet rollback so it cannot mask the original exception.
     *
     * @param callback the transactional work, receiving the transaction-bound connection
     * @param <T>      the callback's return type
     * @return the callback's result
     * @throws JForgeException if the transaction cannot be begun, committed or rolled back,
     *                      or the callback throws SQLException
     */
    private <T> T inTransaction(ConnectionCallback<T> callback) {
        Connection conn = beginTransaction();
        try {
            T result = callback.doInConnection(conn);
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
            throw new JForgeException(
                    JForgeException.Code.SQL,
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
     * Shared connection-scope lifecycle, the single implementation of the
     * borrow/release semantics behind every {@code executeWithoutTransaction}/
     * {@code runWithoutTransaction} variant: borrows one connection, runs the
     * body, and returns the connection to the pool in a {@code finally} — success
     * or failure. Auto-commit stays enabled (no atomicity); an
     * {@link SQLException} from the body is wrapped into {@link JForgeException}.
     *
     * @param callback the work to run on the shared connection
     * @param <T>      the callback's return type
     * @return the callback's result
     * @throws io.github.erdsgfc.jforge.JForgeException if the connection cannot be obtained,
     *                                  or the callback throws {@link SQLException}
     */
    private <T> T inScope(ConnectionCallback<T> callback) {
        Connection conn = beginConnectionScope();
        try {
            return callback.doInConnection(conn);
        } catch (SQLException e) {
            // Match the execute() contract: JDBC failures from the callback are
            // wrapped into JForgeException. The scope connection is still returned to
            // the pool by the finally block.
            throw new JForgeException(
                    JForgeException.Code.SQL,
                    "Connection scope failed" + (e.getMessage() != null ? ": " + e.getMessage() : ""), e);
        } finally {
            endConnectionScope();
        }
    }

    /**
     * Rolls back the active transaction, swallowing a "no transaction" failure.
     * Used by {@link #inTransaction} so a commit failure does not hide the primary
     * error.
     */
    private void rollbackQuietly() {
        try {
            rollback();
        } catch (RuntimeException ignored) {
            // Best effort: the commit failure path already cleared the thread state.
        }
    }
}
