package io.github.erdsgfc.jforge.starter;

import io.github.erdsgfc.jforge.OrmException;
import io.github.erdsgfc.jforge.TransactionManager;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * {@link TransactionManager} backed by Spring's {@link PlatformTransactionManager},
 * installed by the {@code jforge-spring-boot-starter} auto-configuration in place of
 * the built-in {@link io.github.erdsgfc.jforge.SimpleTransactionManager}.
 *
 * <p>Every method delegates to the standard Spring utilities so a transaction begun
 * here fully participates in Spring's transaction infrastructure:
 * <ul>
 *   <li>{@link #connection} uses {@link DataSourceUtils#getConnection} — inside an
 *       active Spring transaction on the same data source it returns the shared
 *       transaction connection, otherwise a fresh pooled connection.</li>
 *   <li>{@link #release} uses {@link DataSourceUtils#releaseConnection} — the
 *       transaction-bound connection is kept open, any other connection is closed.</li>
 *   <li>{@link #begin} starts the transaction with default propagation
 *       ({@code PROPAGATION_REQUIRED}), so calling it inside an existing Spring
 *       transaction (e.g. a {@code @Transactional} service method) joins that
 *       transaction instead of starting a new one.</li>
 * </ul>
 * The thread-bound {@link TransactionStatus} mirrors the connection-ownership model
 * of {@code SimpleTransactionManager}: {@code beginTransaction()} without a matching
 * {@code commit()/rollback()} leaks the status on the thread (the same abandoned
 * transaction contract, best avoided through the {@code execute} template).</p>
 */
public final class SpringTransactionManager implements TransactionManager, SmartInitializingSingleton {

    /**
     * Transaction definition shared by every ORM-level transaction: default
     * propagation ({@code PROPAGATION_REQUIRED}), no isolation or timeout override.
     * Effectively immutable (no setter is ever called), so a single {@code static
     * final} instance is safe to share and avoids a per-call allocation — following
     * this project's principle that {@code static final} references let the JIT
     * constant-fold.
     */
    private static final TransactionDefinition DEFINITION = new DefaultTransactionDefinition();

    /** The Spring transaction manager this wrapper delegates to. */
    private final PlatformTransactionManager delegate;

    /**
     * Thread-bound status of the transaction begun through the ORM API. {@code null}
     * when no ORM-level transaction is active on this thread. Tying the status to the
     * thread lets multiple repositories sharing the thread share one transaction —
     * the same ownership model as {@code SimpleTransactionManager}.
     */
    private final ThreadLocal<TransactionStatus> status = new ThreadLocal<>();

    /**
     * Creates a wrapper around the given Spring transaction manager.
     *
     * @param delegate the Spring transaction manager to delegate to; must be bound to
     *                 the same {@code DataSource} the ORM repositories use
     */
    public SpringTransactionManager(PlatformTransactionManager delegate) {
        this.delegate = delegate;
    }

    /**
     * Installs this wrapper as the global {@link TransactionManager} singleton.
     * Spring invokes this once after <em>every</em> singleton bean has been created
     * and initialized, so the auto-configured {@code PlatformTransactionManager}
     * and any user beans are guaranteed ready. Every repository created afterwards —
     * and every {@code TransactionManager.current()} call in generated code —
     * transparently uses Spring's transaction management.
     */
    @Override
    public void afterSingletonsInstantiated() {
        TransactionManager.set(this);
    }

    /**
     * Returns the connection for the given data source: the Spring-bound transaction
     * connection when one is active, or a fresh pooled connection otherwise.
     *
     * <p>{@code DataSourceUtils.getConnection} inspects Spring's
     * {@code TransactionSynchronizationManager} for a resource bound to this data
     * source — a repository bound to a different data source gets its own pooled
     * connection and stays outside the active transaction, matching the
     * {@code SimpleTransactionManager} isolation semantics.</p>
     *
     * @param dataSource the data source to obtain a connection from
     * @return a usable {@link Connection} participating in any active transaction
     * @throws OrmException if the connection cannot be obtained
     */
    @Override
    public Connection connection(DataSource dataSource) {
        try {
            return DataSourceUtils.getConnection(dataSource);
        } catch (CannotGetJdbcConnectionException e) {
            throw new OrmException("Cannot obtain connection", e);
        }
    }

    /**
     * Releases a connection obtained via {@link #connection(DataSource)}: closes it
     * unless it is the transaction-bound connection, which stays open until
     * {@code commit()/rollback()} ends the transaction.
     *
     * @param conn       the connection to release
     * @param dataSource the data source the connection was obtained from
     */
    @Override
    public void release(Connection conn, DataSource dataSource) {
        try {
            DataSourceUtils.releaseConnection(conn, dataSource);
        } catch (CannotGetJdbcConnectionException ignored) {
            // best effort: a failed close must not mask the caller's result
        }
    }

    /**
     * Starts a new transaction. The data source parameter is unused here — the
     * wrapped {@link PlatformTransactionManager} already knows its data source and
     * Spring resolves the connection through {@link DataSourceUtils} at first use.
     *
     * <p>Uses {@code PROPAGATION_REQUIRED}: when a Spring transaction is already
     * active on this thread (e.g. a {@code @Transactional} service method) the call
     * joins it and the eventual {@code commit()/rollback()} becomes a no-op or a
     * rollback-only marker. A nested ORM-level begin (another {@code begin} without
     * an intervening commit/rollback) is rejected.</p>
     *
     * <p>Registers a transaction completion hook so a transaction begun manually and
     * left to an outer Spring {@code @Transactional} boundary (no {@code commit()/
     * rollback()} call) cannot leak a stale status: without it, the same pooled
     * thread would report a spurious "already active" on its next transaction.</p>
     *
     * @param dataSource the data source the transaction belongs to (unused)
     * @throws OrmException if a transaction is already active on this thread, or the
     *                      transaction cannot be started
     */
    @Override
    public void begin(DataSource dataSource) {
        if (status.get() != null) {
            throw new OrmException("A transaction is already active on this thread");
        }
        try {
            status.set(delegate.getTransaction(DEFINITION));
        } catch (RuntimeException e) {
            throw new OrmException("Cannot begin transaction", e);
        }
        // Synchronizations are active right after getTransaction (Spring initialized
        // them), so registration is safe. The hook clears our status when the Spring
        // transaction completes — however it ends — even if the caller never called
        // commit()/rollback().
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int completionStatus) {
                    status.remove();
                }
            });
        }
    }

    /**
     * Commits the active transaction and clears the thread-bound status. When the
     * transaction joined an outer Spring transaction (not started here), the commit
     * is delegated to Spring and behaves as a no-op on the participating status.
     *
     * @throws OrmException if no transaction is active, or the commit fails
     */
    @Override
    public void commit() {
        TransactionStatus txStatus = status.get();
        if (txStatus == null) {
            throw new OrmException("No active transaction to commit");
        }
        try {
            delegate.commit(txStatus);
        } catch (RuntimeException e) {
            throw new OrmException("Commit failed", e);
        } finally {
            // Always detach, even on failure, so the thread never leaks the status.
            status.remove();
        }
    }

    /**
     * Rolls back the active transaction and clears the thread-bound status. When the
     * transaction joined an outer Spring transaction, the rollback marks that
     * transaction rollback-only so Spring discards it at the service boundary.
     *
     * @throws OrmException if no transaction is active, or the rollback fails
     */
    @Override
    public void rollback() {
        TransactionStatus txStatus = status.get();
        if (txStatus == null) {
            throw new OrmException("No active transaction to rollback");
        }
        try {
            delegate.rollback(txStatus);
        } catch (RuntimeException e) {
            throw new OrmException("Rollback failed", e);
        } finally {
            // Always detach, even on failure, so the thread never leaks the status.
            status.remove();
        }
    }

    /**
     * Returns whether a transaction is currently active on this thread: either one
     * begun via the ORM API, or an outer Spring transaction (e.g. a
     * {@code @Transactional} service method) the repository calls are participating
     * in. The latter is detected through
     * {@link TransactionSynchronizationManager#isActualTransactionActive()}.
     *
     * @return {@code true} when a transaction is active on this thread
     */
    @Override
    public boolean isActive() {
        return status.get() != null || TransactionSynchronizationManager.isActualTransactionActive();
    }

    @Override
    public void markRollbackOnly() {
        TransactionStatus txStatus = status.get();
        if (txStatus == null) {
            throw new OrmException("No active transaction to mark rollback-only");
        }
        // On a participating status (joined an outer @Transactional transaction) this
        // marks the outer transaction rollback-only — standard Spring semantics.
        txStatus.setRollbackOnly();
    }

    @Override
    public boolean isRollbackOnly() {
        TransactionStatus txStatus = status.get();
        return txStatus != null && txStatus.isRollbackOnly();
    }
}