package io.github.erdsgfc.jforge.starter;

import io.github.erdsgfc.jforge.JForgeException;
import io.github.erdsgfc.jforge.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * {@link TransactionManager} backed by Spring's {@link PlatformTransactionManager},
 * supplied by the {@code jforge-spring-boot-starter} auto-configuration as the
 * bean injected into generated repositories (in place of the built-in
 * {@link io.github.erdsgfc.jforge.SimpleTransactionManager}).
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
public final class SpringTransactionManager implements TransactionManager {

    private static final Logger LOG = LoggerFactory.getLogger(SpringTransactionManager.class);

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
     * Thread-bound nesting depth of connection scopes begun by this wrapper. Only
     * scopes that bound their own {@link ConnectionHolder} (no active Spring
     * transaction) are counted, so {@link #endScope} can tell "still inside an
     * outer scope" (decrement) from "scope joined a transaction" (nothing bound,
     * nothing to clean). Scope state itself lives in Spring's
     * {@code TransactionSynchronizationManager}, keyed by data source.
     */
    private final ThreadLocal<Integer> scopeDepth = new ThreadLocal<>();

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
     * @throws JForgeException if the connection cannot be obtained
     */
    @Override
    public Connection connection(DataSource dataSource) {
        try {
            return DataSourceUtils.getConnection(dataSource);
        } catch (CannotGetJdbcConnectionException e) {
            throw new JForgeException(JForgeException.Code.CONNECTION, "Cannot obtain connection", e);
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
     * @throws JForgeException if a transaction is already active on this thread, or the
     *                      transaction cannot be started
     */
    @Override
    public void begin(DataSource dataSource) {
        if (status.get() != null) {
            throw new JForgeException(JForgeException.Code.TRANSACTION, "A transaction is already active on this thread");
        }
        try {
            status.set(delegate.getTransaction(DEFINITION));
            LOG.debug("Transaction begun");
        } catch (RuntimeException e) {
            throw new JForgeException(JForgeException.Code.TRANSACTION, "Cannot begin transaction", e);
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
     * @throws JForgeException if no transaction is active, or the commit fails
     */
    @Override
    public void commit() {
        TransactionStatus txStatus = status.get();
        if (txStatus == null) {
            throw new JForgeException(JForgeException.Code.TRANSACTION, "No active transaction to commit");
        }
        try {
            delegate.commit(txStatus);
            LOG.debug("Transaction committed");
        } catch (RuntimeException e) {
            throw new JForgeException(JForgeException.Code.TRANSACTION, "Commit failed", e);
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
     * @throws JForgeException if no transaction is active, or the rollback fails
     */
    @Override
    public void rollback() {
        TransactionStatus txStatus = status.get();
        if (txStatus == null) {
            throw new JForgeException(JForgeException.Code.TRANSACTION, "No active transaction to rollback");
        }
        try {
            delegate.rollback(txStatus);
            LOG.debug("Transaction rolled back");
        } catch (RuntimeException e) {
            throw new JForgeException(JForgeException.Code.TRANSACTION, "Rollback failed", e);
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
            throw new JForgeException(JForgeException.Code.TRANSACTION, "No active transaction to mark rollback-only");
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

    // ---- Connection scope (no transaction) ----------------------------------

    /**
     * Begins a connection scope: binds a fresh pooled connection to this thread
     * (as a {@link ConnectionHolder}) so every repository call on the data source
     * shares it, until {@link #endScope}. The connection keeps auto-commit
     * enabled — a scope only saves pool round-trips, it provides no atomicity.
     *
     * <p>When a Spring transaction is already active on this data source, the
     * scope becomes a no-op and returns the transaction connection — the same
     * join semantics as {@link #begin}. When an outer scope already bound a
     * holder (nested scope), the outer connection is reused and the nesting is
     * counted in {@link #scopeDepth}. Binding a plain holder without an active
     * transaction is the same mechanism MyBatis-Spring uses for non-transactional
     * sessions; {@code DataSourceUtils} treats it as the shared connection and
     * {@code releaseConnection} keeps it open until the holder is unbound.</p>
     *
     * @param dataSource the data source the scope's connection is borrowed from
     * @return the shared scope connection, owned by the scope — do not close it
     *         directly, and do not use it after {@link #endScope}
     * @throws JForgeException if the connection cannot be obtained
     */
    @Override
    public Connection beginScope(DataSource dataSource) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.hasResource(dataSource)) {
            // An active Spring transaction owns the connection for this data
            // source: the scope is a no-op — endScope() is then also a no-op.
            return DataSourceUtils.getConnection(dataSource);
        }
        if (TransactionSynchronizationManager.hasResource(dataSource)) {
            // No active transaction but a holder is bound: an outer scope on this
            // thread. Reuse its connection and count the nesting.
            scopeDepth.set(scopeDepth.get() + 1);
            return DataSourceUtils.getConnection(dataSource);
        }
        try {
            Connection conn = dataSource.getConnection();
            TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(conn));
            scopeDepth.set(1);
            return conn;
        } catch (SQLException e) {
            throw new JForgeException(JForgeException.Code.CONNECTION, "Cannot obtain connection", e);
        }
    }

    /**
     * Ends the connection scope begun by {@link #beginScope}: unbinds the holder
     * this wrapper bound and returns the connection to the pool. A no-op when the
     * scope joined an active Spring transaction (nothing was bound), or when the
     * thread is still inside an outer scope (nesting decremented only).
     *
     * @param dataSource the data source the scope was begun on
     */
    @Override
    public void endScope(DataSource dataSource) {
        Integer depth = scopeDepth.get();
        if (depth == null) {
            return; // scope joined an active Spring transaction — nothing of ours to clean
        }
        if (depth > 1) {
            scopeDepth.set(depth - 1);
            return; // still inside an outer scope on this thread
        }
        scopeDepth.remove();
        Object resource = TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
        if (resource instanceof ConnectionHolder holder) {
            try {
                holder.getConnection().close();
            } catch (SQLException ignored) {
                // best effort: a failed close must not mask the caller's result
            }
        }
    }
}