package io.github.erdsgfc.jforge.starter;

import io.github.erdsgfc.jforge.core.JForgeException;
import io.github.erdsgfc.jforge.core.SimpleTransactionManager;
import io.github.erdsgfc.jforge.core.TransactionManager;
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
 * 基于 Spring 的 {@link PlatformTransactionManager} 实现的 {@link TransactionManager}，
 * 由 {@code jforge-spring-boot-starter} 自动配置提供，作为注入到生成仓库的 Bean
 * （取代内置的 {@link SimpleTransactionManager}）。
 *
 * <p>每个方法都委托给标准 Spring 工具类，因此在此开始的事务完全参与 Spring 的
 * 事务设施：
 * <ul>
 *   <li>{@link #connection} 使用 {@link DataSourceUtils#getConnection} —— 在同一数据源
 *       上存在活动 Spring 事务时返回共享的事务连接，否则返回新的池化连接。</li>
 *   <li>{@link #release} 使用 {@link DataSourceUtils#releaseConnection} —— 事务绑定的
 *       连接保持打开，其他连接被关闭。</li>
 *   <li>{@link #begin} 以默认传播行为（{@code PROPAGATION_REQUIRED}）开始事务，因此在
 *       已有 Spring 事务（如 {@code @Transactional} 服务方法）内调用会加入该事务，
 *       而不是新开一个。</li>
 * </ul>
 * 线程绑定的 {@link TransactionStatus} 复刻了 {@code SimpleTransactionManager} 的
 * 连接所有权模型：{@code beginTransaction()} 没有配对的 {@code commit()/rollback()}
 * 会在线程上遗留状态（与遗留事务契约相同，最好通过 {@code execute} 模板避免）。</p>
 */
public final class SpringTransactionManager implements TransactionManager {

    private static final Logger LOG = LoggerFactory.getLogger(SpringTransactionManager.class);

    /**
     * 所有 ORM 级事务共享的事务定义：默认传播行为（{@code PROPAGATION_REQUIRED}），
     * 不覆盖隔离级别或超时。实际上不可变（从不调用 setter），因此单个 {@code static
     * final} 实例可以安全共享并避免每次调用分配对象——遵循本项目"static final 引用
     * 便于 JIT 常量折叠"的原则。
     */
    private static final TransactionDefinition DEFINITION = new DefaultTransactionDefinition();

    /** 该包装器委托的 Spring 事务管理器。 */
    private final PlatformTransactionManager delegate;

    /**
     * 通过 ORM API 开始的事务的线程绑定状态。当本线程上没有活动的 ORM 级事务时为
     * {@code null}。将状态绑定到线程，使共享同一线程的多个仓库共享一个事务——
     * 与 {@code SimpleTransactionManager} 相同的所有权模型。
     */
    private final ThreadLocal<TransactionStatus> status = new ThreadLocal<>();

    /**
     * 由本包装器开始的连接作用域的线程绑定嵌套深度。只有绑定了自己的
     * {@link ConnectionHolder}（没有活动 Spring 事务）的作用域才被计数，因此
     * {@link #endScope} 可以区分"仍在外层作用域内"（递减）与"作用域已加入事务"
     * （未绑定任何东西，无需清理）。作用域状态本身存放在 Spring 的
     * {@code TransactionSynchronizationManager} 中，以数据源为键。
     */
    private final ThreadLocal<Integer> scopeDepth = new ThreadLocal<>();

    /**
     * 创建给定 Spring 事务管理器的包装器。
     *
     * @param delegate 要委托的 Spring 事务管理器；必须绑定到 ORM 仓库使用的同一个
     *                 {@code DataSource}
     */
    public SpringTransactionManager(PlatformTransactionManager delegate) {
        this.delegate = delegate;
    }

    /**
     * 返回给定数据源的连接：有活动的 Spring 绑定事务连接时返回之，否则返回新的池化连接。
     *
     * <p>{@code DataSourceUtils.getConnection} 会在 Spring 的
     * {@code TransactionSynchronizationManager} 中查找绑定到该数据源的资源——绑定到不同
     * 数据源的仓库会获得自己的池化连接并停留在活动事务之外，与
     * {@code SimpleTransactionManager} 的隔离语义一致。</p>
     *
     * @param dataSource 获取连接的数据源
     * @return 一个可用的 {@link Connection}，参与任何活动事务
     * @throws JForgeException 如果无法获取连接
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
     * 释放通过 {@link #connection(DataSource)} 获取的连接：除事务绑定的连接外均关闭之，
     * 事务绑定的连接保持打开，直到 {@code commit()/rollback()} 结束事务。
     *
     * @param conn       要释放的连接
     * @param dataSource 获取该连接的数据源
     */
    @Override
    public void release(Connection conn, DataSource dataSource) {
        try {
            DataSourceUtils.releaseConnection(conn, dataSource);
        } catch (CannotGetJdbcConnectionException ignored) {
            // 尽力而为：关闭失败不得掩盖调用方的结果
        }
    }

    /**
     * 开始一个新事务。此处不使用数据源参数——被包装的 {@link PlatformTransactionManager}
     * 已经知道自己的数据源，Spring 在首次使用时通过 {@link DataSourceUtils} 解析连接。
     *
     * <p>使用 {@code PROPAGATION_REQUIRED}：当本线程已有活动 Spring 事务（如
     * {@code @Transactional} 服务方法）时，该调用加入它，随后的 {@code commit()/rollback()}
     * 变为 no-op 或 rollback-only 标记。嵌套的 ORM 级 begin（没有中间 commit/rollback
     * 的又一次 {@code begin}）被拒绝。</p>
     *
     * <p>注册事务完成钩子，使手动开始但交由外层 Spring {@code @Transactional} 边界结束
     * （未调用 {@code commit()/rollback()}）的事务不会遗留过期状态：没有该钩子，
     * 同一个池化线程下次开启事务时会误报 "already active"。</p>
     *
     * @param dataSource 事务所属的数据源（未使用）
     * @throws JForgeException 如果本线程已有活动事务，或无法开始事务
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
        // getTransaction 之后同步机制立即可用（Spring 已初始化它们），因此注册是安全的。
        // 无论 Spring 事务以何种方式结束——即使调用方从未调用 commit()/rollback()——
        // 该钩子都会在事务完成时清除我们的状态。
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
     * 提交活动事务并清除线程绑定状态。当事务加入了外层 Spring 事务（非在此开始）时，
     * 提交被委托给 Spring，对参与的状态表现为 no-op。
     *
     * @throws JForgeException 如果没有活动事务，或提交失败
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
            // 即使失败也始终解除绑定，确保线程不会遗留状态。
            status.remove();
        }
    }

    /**
     * 回滚活动事务并清除线程绑定状态。当事务加入了外层 Spring 事务时，回滚将该事务
     * 标记为 rollback-only，使 Spring 在服务边界将其丢弃。
     *
     * @throws JForgeException 如果没有活动事务，或回滚失败
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
            // 即使失败也始终解除绑定，确保线程不会遗留状态。
            status.remove();
        }
    }

    /**
     * 返回本线程当前是否有活动事务：要么是通过 ORM API 开始的事务，要么是仓库调用
     * 正在参与的外层 Spring 事务（如 {@code @Transactional} 服务方法）。后者通过
     * {@link TransactionSynchronizationManager#isActualTransactionActive()} 检测。
     *
     * @return 当本线程有活动事务时返回 {@code true}
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
        // 对于参与的状态（已加入外层 @Transactional 事务），这会把外层事务标记为
        // rollback-only——标准的 Spring 语义。
        txStatus.setRollbackOnly();
    }

    @Override
    public boolean isRollbackOnly() {
        TransactionStatus txStatus = status.get();
        return txStatus != null && txStatus.isRollbackOnly();
    }

    // ---- 连接作用域（无事务） ----------------------------------

    /**
     * 开始一个连接作用域：将一个全新的池化连接绑定到本线程（作为 {@link ConnectionHolder}），
     * 使该数据源上的所有仓库调用共享它，直到 {@link #endScope}。连接保持启用
     * auto-commit——作用域只节省池往返，不提供原子性。
     *
     * <p>当该数据源已有活动 Spring 事务时，作用域变为 no-op 并返回事务连接——与
     * {@link #begin} 相同的 join 语义。当外层作用域已绑定 holder（嵌套作用域）时，
     * 复用外层连接并在 {@link #scopeDepth} 中计数嵌套。在没有活动事务时绑定普通 holder，
     * 与 MyBatis-Spring 用于非事务会话的机制相同；{@code DataSourceUtils} 将其视为共享
     * 连接，{@code releaseConnection} 在 holder 解除绑定前保持其打开。</p>
     *
     * @param dataSource 作用域连接所借用的数据源
     * @return 共享的作用域连接，归作用域所有——不要直接关闭它，也不要在
     *         {@link #endScope} 之后使用
     * @throws JForgeException 如果无法获取连接
     */
    @Override
    public Connection beginScope(DataSource dataSource) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.hasResource(dataSource)) {
            // 活动 Spring 事务拥有该数据源的连接：作用域是 no-op——
            // 因此 endScope() 也是 no-op。
            return DataSourceUtils.getConnection(dataSource);
        }
        if (TransactionSynchronizationManager.hasResource(dataSource)) {
            // 没有活动事务但已绑定 holder：本线程上有外层作用域。
            // 复用其连接并计数嵌套。
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
     * 结束由 {@link #beginScope} 开始的连接作用域：解除本包装器绑定的 holder 并将
     * 连接归还池中。当作用域已加入活动 Spring 事务（未绑定任何东西），或线程仍处于
     * 外层作用域内（仅递减嵌套）时为 no-op。
     *
     * @param dataSource 作用域开始时所在的数据源
     */
    @Override
    public void endScope(DataSource dataSource) {
        Integer depth = scopeDepth.get();
        if (depth == null) {
            return; // 作用域已加入活动 Spring 事务——没有需要我们清理的东西
        }
        if (depth > 1) {
            scopeDepth.set(depth - 1);
            return; // 本线程仍在外层作用域内
        }
        scopeDepth.remove();
        Object resource = TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
        if (resource instanceof ConnectionHolder holder) {
            try {
                holder.getConnection().close();
            } catch (SQLException ignored) {
                // 尽力而为：关闭失败不得掩盖调用方的结果
            }
        }
    }
}