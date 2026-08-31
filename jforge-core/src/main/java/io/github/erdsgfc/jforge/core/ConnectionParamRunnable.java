package io.github.erdsgfc.jforge.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 无返回值、由框架提供 {@link Connection} 并附带外部参数的回调,经
 * {@link TransactionOperations#run(Object, ConnectionParamRunnable)} 在事务内执行——
 * 或经
 * {@link TransactionOperations#runWithoutTransaction(Object, ConnectionParamRunnable)}
 * 在无事务的单个共享连接上执行。是 {@link ConnectionParamCallback} 的对应物,用于
 * 只需副作用、无需 {@code return null} 的工作体。
 *
 * <p>回调接收事务绑定(或作用域共享)的 {@link Connection} 与用户提供的参数;
 * {@link SQLException} 会被包装为 {@link JForgeException}
 * 并回滚(作用域场景则无回滚——已执行的语句保持已提交)。</p>
 *
 * @param <P> 外部提供的参数类型
 */
@FunctionalInterface
public interface ConnectionParamRunnable<P> {

    /**
     * 在共享连接上、使用提供的参数执行工作。
     *
     * @param conn  绑定到活动事务的连接,或由活动连接作用域共享的连接
     * @param param 外部提供的参数
     * @throws SQLException 若 JDBC 操作失败
     */
    void doInConnection(Connection conn, P param) throws SQLException;
}
