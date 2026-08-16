package io.github.erdsgfc.jforge.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 由框架提供 {@link Connection}、同时附带外部参数的回调,经
 * {@link TransactionOperations#execute(Object, ConnectionParamCallback)} 执行。
 *
 * <p>与 {@link ConnectionCallback} 类似,但回调额外接收一个用户提供的参数
 * (任意类型),因此无需在闭包中捕获即可把外部状态传入工作体。</p>
 *
 * @param <T> 工作体的返回类型
 * @param <P> 外部提供的参数类型
 */
@FunctionalInterface
public interface ConnectionParamCallback<T, P> {

    /**
     * 在共享连接上、使用提供的参数执行工作。可能抛出 {@link SQLException}——
     * 调用方会将其包装为 {@link io.github.erdsgfc.jforge.JForgeException} 并回滚。
     *
     * @param conn  绑定到活动事务的连接,或由活动连接作用域共享的连接
     * @param param 外部提供的参数
     * @return 从 {@link TransactionOperations#execute} 返回的值,或当工作体
     *         只做副作用时返回 {@code null}
     * @throws SQLException 若 JDBC 操作失败
     */
    T doInConnection(Connection conn, P param) throws SQLException;
}
