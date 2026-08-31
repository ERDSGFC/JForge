package io.github.erdsgfc.jforge.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 由框架提供 {@link Connection} 的回调,经
 * {@link TransactionOperations#execute(ConnectionCallback)} 在编程式事务内执行——
 * 或经
 * {@link TransactionOperations#executeWithoutTransaction(ConnectionCallback)}
 * 在无事务的单个共享连接上执行。两种上下文的函数形态完全一致,因此同一个 lambda
 * 可以不加修改地用于事务与作用域两种场景。
 *
 * <p>回调接收事务绑定(或作用域共享)的 {@link Connection},因此可以做 ORM 刻意
 * 不抽象的裸 JDBC 控制——隔离级别、savepoint、只读、查询超时或参与事务的直接 SQL。
 * 回调不得关闭连接;生命周期由 {@code execute} 管理。</p>
 *
 * @param <T> 工作体的返回类型
 */
@FunctionalInterface
public interface ConnectionCallback<T> {

    /**
     * 在共享连接上执行工作。可能抛出 {@link SQLException}——
     * {@link TransactionOperations#execute} 会将其包装为
     * {@link JForgeException} 并回滚;任何非受检异常同样
     * 触发回滚并原样传播。
     *
     * @param conn 绑定到活动事务的连接,或由活动连接作用域共享的连接
     * @return 从 {@link TransactionOperations#execute} 返回的值,或当工作体
     *         只做副作用时返回 {@code null}
     * @throws SQLException 若 JDBC 操作失败
     */
    T doInConnection(Connection conn) throws SQLException;
}
