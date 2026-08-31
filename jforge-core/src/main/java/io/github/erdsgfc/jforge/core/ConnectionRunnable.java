package io.github.erdsgfc.jforge.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 无返回值、由框架提供 {@link Connection} 的回调,经
 * {@link TransactionOperations#run(ConnectionRunnable)} 在编程式事务内执行——
 * 或经
 * {@link TransactionOperations#runWithoutTransaction(ConnectionRunnable)}
 * 在无事务的单个共享连接上执行。是 {@link ConnectionCallback} 的对应物,用于
 * 只需副作用、无需 {@code return null} 的工作体。
 *
 * <p>与 {@link ConnectionCallback} 一样,回调接收事务绑定(或作用域共享)的
 * {@link Connection} 做裸 JDBC 控制;{@link SQLException} 会被包装为
 * {@link JForgeException} 并回滚(作用域场景则无回滚——
 * 已执行的语句保持已提交)。</p>
 */
@FunctionalInterface
public interface ConnectionRunnable {

    /**
     * 在共享连接上执行工作。
     *
     * @param conn 绑定到活动事务的连接,或由活动连接作用域共享的连接
     * @throws SQLException 若 JDBC 操作失败
     */
    void doInConnection(Connection conn) throws SQLException;
}
