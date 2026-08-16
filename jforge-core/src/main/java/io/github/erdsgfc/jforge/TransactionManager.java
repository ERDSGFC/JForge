package io.github.erdsgfc.jforge;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 可插拔的事务管理器——用 Spring 的 {@code PlatformTransactionManager} 替换内置线程局部
 * 事务的唯一扩展点。
 *
 * <p>管理器 <em>按实例</em> 传递：{@link JForge} 以 {@code private final} 字段持有它，并把它
 * 交给创建的每个仓库，生成的实现因此存入自己的 {@code final} 字段（利于 JIT，无静态查找）。
 * Spring Boot starter 以同样的方式提供其 Spring 感知的实现——作为注入生成仓库的 bean。
 * 不存在全局单例，构造之后也无法更换管理器。</p>
 */
public interface TransactionManager {

    /** 返回当前事务连接，或从连接池取回的新的 auto-commit 连接。 */
    Connection connection(DataSource dataSource);

    /**
     * 释放经 {@link #connection(DataSource)} 获取的连接。
     *
     * <p>传入数据源是为了让 Spring 版实现可以委托给
     * {@code DataSourceUtils.releaseConnection(conn, dataSource)}——它需要数据源来判断该连接
     * 是事务绑定的（为事务保持打开）还是应归还的池化连接。</p>
     *
     * @param conn       要释放的连接
     * @param dataSource 该连接获取来源的数据源
     */
    void release(Connection conn, DataSource dataSource);

    /** 开启一个新事务。 */
    void begin(DataSource dataSource);

    /** 提交当前活动事务。 */
    void commit();

    /** 回滚当前活动事务。 */
    void rollback();

    /** 当本线程有活动事务时返回 {@code true}。 */
    boolean isActive();

    /**
     * 不抛出异常地标记当前事务为回滚：即使调用方正常返回，事务完成时也会被回滚。用于违反
     * 业务规则时中止，同时仍能从事务回调返回结果。
     *
     * @throws JForgeException 若本线程没有活动事务
     */
    void markRollbackOnly();

    /**
     * 返回当前活动事务是否已通过 {@link #markRollbackOnly()} 标记为回滚。
     *
     * @return 当前活动事务被标记为回滚时返回 {@code true}
     */
    boolean isRollbackOnly();

    // ---- 连接作用域（无事务） -------------------------------------------------

    /**
     * 在当前线程上开启连接作用域：从数据源借用单个连接并绑定到线程，使后续所有仓库调用共享它，
     * 直到 {@link #endScope(DataSource)}。与事务不同，连接保持其 auto-commit 设置，因此每条
     * SQL 语句独立提交——作用域只是省去连接池往返，不提供原子性。
     *
     * <p>在同一数据源上有活动事务时开启作用域，会返回事务连接且不绑定任何状态——作用域退化为
     * no-op，{@link #endScope(DataSource)} 也无须清理。同一数据源上的嵌套作用域复用外层作用域
     * 的连接。作用域激活期间开启事务会被拒绝。</p>
     *
     * @param dataSource 作用域连接借用的数据源
     * @return 共享的作用域连接，归作用域所有——不要直接关闭它，也不要在
     *         {@link #endScope(DataSource)} 之后使用
     * @throws JForgeException 若无法获取连接
     */
    Connection beginScope(DataSource dataSource);

    /**
     * 结束由 {@link #beginScope(DataSource)} 开启的连接作用域：解除作用域连接的绑定并归还
     * 连接池。当线程没有活动作用域（如并入活动事务的作用域）时为 no-op。
     *
     * @param dataSource 作用域开启时所用的数据源
     */
    void endScope(DataSource dataSource);
}