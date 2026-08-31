package io.github.erdsgfc.jforge.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 每个仓库都继承的编程式事务契约。
 *
 * <p>用户的 {@code @Dao} 接口继承 {@link BaseRepository}，而后者继承本接口，因此每个生成的
 * 仓库都暴露 {@code beginTransaction/commit/rollback/isTransactionActive} 及 {@link #execute}
 * 模板。事务存活于当前线程（由全局 {@link TransactionManager} 支撑）；
 * 激活期间，绑定到同一 {@code DataSource} 的所有仓库共享一个连接与一个事务边界，
 * 因此多仓库工作可包进单个事务。</p>
 *
 * <p>不支持 ORM 层级的嵌套事务：本线程上经此 API 开启的事务仍处于活动状态时再调用
 * {@link #beginTransaction()} 会抛出 {@link io.github.erdsgfc.jforge.core.JForgeException}。
 * 安装了 {@code jforge-spring-boot-starter} 后，在外层 Spring 事务（如 {@code @Transactional}
 * 服务方法）内调用则改为加入该事务（{@code PROPAGATION_REQUIRED}）。</p>
 *
 * <p>事务族（{@code execute}/{@code run}）与连接作用域族（{@code executeWithoutTransaction}/
 * {@code runWithoutTransaction}）都覆盖完整的方法形态矩阵——带或不带外部参数、带或不带返回值、
 * 是否向回调暴露 {@link Connection}。所有变体各自委托给一个私有核心（{@link #inTransaction} /
 * {@link #inScope}），使 commit/rollback（或借/还）逻辑只存在于一处。</p>
 */
public interface TransactionOperations {

    /**
     * 在当前线程开启新事务：从仓库的数据源获取连接、关闭 auto-commit，并绑定到线程，
     * 使后续所有仓库调用都加入该事务。
     *
     * <p>返回事务绑定的 {@link Connection}，让调用方可以进行原生 JDBC 控制（隔离级别、保存点、
     * 直接 SQL）——默认的 {@link #execute} 也会把它交给回调。连接归事务所有：不要直接关闭它，
     * 也不要在 {@link #commit()} 或 {@link #rollback()} 之后使用。</p>
     *
     * <p>安装了 {@code jforge-spring-boot-starter} 后，在已激活的 Spring 事务内调用本方法会
     * 加入该事务（{@code PROPAGATION_REQUIRED}）而非抛出；抛出仅保留给中间没有 commit/rollback
     * 的第二次 ORM 层级 begin。</p>
     *
     * @return 绑定到新开启事务的连接
     * @throws io.github.erdsgfc.jforge.core.JForgeException 若本线程已有另一个 ORM 层级事务处于活动
     *                                  状态，或无法获取连接
     */
    Connection beginTransaction();

    /**
     * 提交当前活动事务并释放其连接。
     *
     * @throws io.github.erdsgfc.jforge.core.JForgeException 若没有活动事务，或提交失败
     */
    void commit();

    /**
     * 回滚当前活动事务并释放其连接。
     *
     * @throws io.github.erdsgfc.jforge.core.JForgeException 若没有活动事务，或回滚失败
     */
    void rollback();

    /**
     * 返回本线程当前是否有活动事务。使用内置 {@code SimpleTransactionManager} 时，仅对经
     * {@link #beginTransaction()} 开启的事务返回 true；安装了 {@code jforge-spring-boot-starter}
     * 后，当线程参与外层 Spring 事务（如 {@code @Transactional} 服务方法）时也返回 true，
     * 通过 Spring 的 {@code TransactionSynchronizationManager} 检测。
     *
     * @return 本线程有活动事务时返回 {@code true}
     */
    boolean isTransactionActive();

    /**
     * 不抛出异常地标记当前事务为回滚：即使 {@link #execute} 正常返回，事务完成时也会被回滚。
     * 用于违反业务规则时中止，同时仍能从回调返回结果。
     *
     * @throws io.github.erdsgfc.jforge.core.JForgeException 若没有活动事务
     */
    void markRollbackOnly();

    /**
     * 返回当前活动事务是否已通过 {@link #markRollbackOnly()} 标记为回滚。安装了 Spring starter
     * 后，当 ORM 加入的外层 Spring 事务被标记为 rollback-only 时同样返回 {@code true}。
     *
     * @return 当前活动事务被标记为回滚时返回 {@code true}
     */
    boolean isRollbackOnly();

    // ---- 事务族:execute/run ----------------------------------------------

    /**
     * 在事务内运行 {@code callback}：开启事务，以事务绑定的 {@link Connection} 调用回调，
     * 成功后提交。若回调或提交抛出异常，则回滚事务并传播异常——来自回调的 {@link SQLException}
     * 被包装为 {@link JForgeException}；任何 {@link RuntimeException} 或 {@link Error}
     * 原样传播。
     *
     * <p>{@code Connection} 参数赋予调用方 ORM 刻意不抽象的原生 JDBC 控制——隔离级别、保存点、
     * 只读、查询超时或参与事务的直接 SQL——同时不暴露连接所有权：{@code execute} 管理连接的
     * 生命周期，底层的获取/释放对在生成实现中保持私有。</p>
     *
     * @param callback 事务工作，接收事务绑定的连接
     * @param <T>      回调的返回类型
     * @return 回调的结果，仅副作用（无返回值）的代码体返回 {@code null}
     * @throws JForgeException 若事务无法开启、提交或回滚，或回调抛出 SQLException
     */
    default <T> T execute(ConnectionCallback<T> callback) {
        return inTransaction(callback);
    }

    /**
     * 在事务内运行 {@code callback}，把外部提供的 {@code param} 与事务绑定的
     * {@link Connection} 一并传给回调。行为与 {@link #execute(ConnectionCallback)} 相同：
     * 成功提交，任何异常都回滚并传播（来自回调的 {@link SQLException} 被包装为
     * {@link JForgeException}）。参数必须是类型化的值，编译器才能推断 {@code P}；字面量
     * {@code null} 需要显式类型（强制转换或显式类型的 lambda 参数）。要运行无参数的事务，
     * 请使用 {@link #execute(ConnectionCallback)}。
     *
     * @param param    外部提供的参数，转发给回调
     * @param callback 事务工作，接收连接与参数
     * @param <T>      回调的返回类型
     * @param <P>      参数类型
     * @return 回调的结果，仅副作用的代码体返回 {@code null}
     * @throws JForgeException 若事务无法开启、提交或回滚，或回调抛出 SQLException
     */
    default <T, P> T execute(P param, ConnectionParamCallback<T, P> callback) {
        return inTransaction(conn -> callback.doInConnection(conn, param));
    }

    /**
     * 在事务内运行 {@code supplier} 但不暴露连接——适用于只调用仓库方法（隐式加入事务）
     * 且不需要原生 JDBC 控制的代码体。无需处理受检异常：没有 {@code Connection} 就没有
     * {@link SQLException} 路径；仓库失败以非受检的 {@link JForgeException} 呈现。
     * 提交/回滚语义与 {@link #execute(ConnectionCallback)} 相同。
     *
     * @param supplier 事务工作，返回一个值
     * @param <T>      结果类型
     * @return supplier 的结果
     * @throws JForgeException 若事务无法开启、提交或回滚，或仓库调用失败
     */
    default <T> T execute(Supplier<T> supplier) {
        return inTransaction(ignored -> supplier.get());
    }

    /**
     * 在事务内运行 {@code function}，把外部提供的 {@code param} 传给它但不传连接——
     * {@link #execute(Supplier)} 的带参版本，适用于只调用仓库方法的代码体。
     * 无需处理受检异常（无 {@link SQLException} 路径）；提交/回滚语义与
     * {@link #execute(Object, ConnectionParamCallback)} 相同。
     *
     * @param param    外部提供的参数，转发给代码体
     * @param function 事务工作，接收参数
     * @param <T>      结果类型
     * @param <P>      参数类型
     * @return function 的结果
     * @throws JForgeException 若事务无法开启、提交或回滚，或仓库调用失败
     */
    default <T, P> T execute(P param, Function<P, T> function) {
        return inTransaction(ignored -> function.apply(param));
    }

    /**
     * 在事务内运行 {@code runnable} 且无返回值——{@link #execute(ConnectionCallback)} 的
     * void 版本，适用于无需 {@code return null} 的仅副作用代码体。行为相同：成功提交，
     * 任何异常都回滚并传播（来自代码体的 {@link SQLException} 被包装为
     * {@link JForgeException}）。
     *
     * @param runnable 事务工作
     * @throws JForgeException 若事务无法开启、提交或回滚，或代码体抛出 SQLException
     */
    default void run(ConnectionRunnable runnable) {
        execute(conn -> {
            runnable.doInConnection(conn);
            return null;
        });
    }

    /**
     * 在事务内运行 {@code runnable}，把外部提供的 {@code param} 与事务绑定的
     * {@link Connection} 一并传给它，且无返回值——{@link #execute(Object, ConnectionParamCallback)}
     * 的 void 版本。
     *
     * @param param    外部提供的参数，转发给代码体
     * @param runnable 事务工作，接收连接与参数
     * @param <P>      参数类型
     * @throws JForgeException 若事务无法开启、提交或回滚，或代码体抛出 SQLException
     */
    default <P> void run(P param, ConnectionParamRunnable<P> runnable) {
        execute(param, (conn, p) -> {
            runnable.doInConnection(conn, p);
            return null;
        });
    }

    /**
     * 在事务内运行 {@code runnable}，无返回值且不暴露连接——{@link #execute(Supplier)}
     * 的 void 版本，适用于只调用仓库方法的仅副作用代码体。无需处理受检异常
     * （无 {@link SQLException} 路径）；提交/回滚语义与 {@link #run(ConnectionRunnable)}
     * 相同。
     *
     * @param runnable 事务工作
     * @throws JForgeException 若事务无法开启、提交或回滚，或仓库调用失败
     */
    default void run(Runnable runnable) {
        execute(ignored -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 在事务内运行 {@code consumer}，把外部提供的 {@code param} 传给它但不传连接，
     * 且无返回值——{@link #execute(Object, Function)} 的 void 版本。无需处理受检异常
     * （无 {@link SQLException} 路径）；提交/回滚语义与
     * {@link #run(Object, ConnectionParamRunnable)} 相同。
     *
     * @param param    外部提供的参数，转发给代码体
     * @param consumer 事务工作，接收参数
     * @param <P>      参数类型
     * @throws JForgeException 若事务无法开启、提交或回滚，或仓库调用失败
     */
    default <P> void run(P param, Consumer<P> consumer) {
        execute(param, (ignored, p) -> {
            consumer.accept(p);
            return null;
        });
    }

    // ---- 作用域族:executeWithoutTransaction/runWithoutTransaction -----------

    /**
     * 在当前线程开启连接作用域：从仓库的数据源借用单个连接并绑定到线程，使所有仓库调用共享
     * 它，直到 {@link #endConnectionScope()}。生成的实现委托给
     * {@code TransactionManager.beginScope(dataSource)}。
     *
     * <p>与 {@link #beginTransaction()} 不同，连接保持 auto-commit 启用：作用域只是省去连接池
     * 往返，不提供原子性。在活动事务（或 Spring {@code @Transactional}）内开启的作用域复用
     * 事务连接，无需清理。</p>
     *
     * @return 共享的作用域连接，归作用域所有——不要直接关闭它，也不要在
     *         {@link #endConnectionScope()} 之后使用
     * @throws io.github.erdsgfc.jforge.core.JForgeException 若无法获取连接
     */
    Connection beginConnectionScope();

    /**
     * 结束由 {@link #beginConnectionScope()} 开启的连接作用域：把作用域连接归还连接池。
     * 当线程没有活动作用域（如并入活动事务的作用域）时为 no-op。
     */
    void endConnectionScope();

    /**
     * 在单个共享连接上运行 {@code callback} 但不开启事务：ORM 从连接池借用一条连接，回调中的
     * 仓库调用复用该连接，回调结束后（无论成败）归还连接池。连接保持 auto-commit 启用，
     * 因此每条 SQL 语句独立提交：与 {@link #execute(ConnectionCallback)} 不同，
     * 这里 <em>没有原子性</em>——回调抛出前已执行的语句保持已提交。
     *
     * <p>回调类型是共享的 {@link ConnectionCallback}——与事务版 {@link #execute(ConnectionCallback)}
     * 的函数形态相同，因此同一个 lambda 在两种上下文中无需改动即可使用。当多语句工作只想省去
     * 每条语句的池往返、不需要回滚语义时用它；当语句必须一起提交或回滚时用
     * {@link #execute(ConnectionCallback)}。作用域内不能开启事务（会抛出
     * {@link JForgeException}）。</p>
     *
     * @param callback 在共享连接上运行的工作
     * @param <T>      回调的返回类型
     * @return 回调的结果，仅副作用的代码体返回 {@code null}
     * @throws io.github.erdsgfc.jforge.core.JForgeException 若无法获取连接，或回调抛出
     *                                  {@link SQLException}
     */
    default <T> T executeWithoutTransaction(ConnectionCallback<T> callback) {
        return inScope(callback);
    }

    /**
     * 在单个共享连接上运行 {@code callback} 但不开启事务，把外部提供的 {@code param} 与共享的
     * {@link Connection} 一并传给它——{@link #executeWithoutTransaction(ConnectionCallback)}
     * 的带参版本。行为相同：借用一条连接，保持 auto-commit（无原子性），在 {@code finally}
     * 中归还连接池；来自回调的 {@link SQLException} 被包装为 {@link JForgeException}。
     *
     * @param param    外部提供的参数，转发给回调
     * @param callback 在共享连接上运行的工作
     * @param <T>      回调的返回类型
     * @param <P>      参数类型
     * @return 回调的结果，仅副作用的代码体返回 {@code null}
     * @throws io.github.erdsgfc.jforge.core.JForgeException 若无法获取连接，或回调抛出
     *                                  {@link SQLException}
     */
    default <T, P> T executeWithoutTransaction(P param, ConnectionParamCallback<T, P> callback) {
        return inScope(conn -> callback.doInConnection(conn, param));
    }

    /**
     * 在单个共享连接上运行 {@code supplier} 但不开启事务，也不暴露连接——适用于只调用仓库方法
     * （隐式共享作用域连接）且不需要原生 JDBC 控制的代码体。无需处理受检异常（无
     * {@link SQLException} 路径）；借/还语义与
     * {@link #executeWithoutTransaction(ConnectionCallback)} 相同。
     *
     * @param supplier 在共享连接上运行的工作，返回一个值
     * @param <T>      结果类型
     * @return supplier 的结果
     * @throws io.github.erdsgfc.jforge.core.JForgeException 若无法获取连接，或仓库调用失败
     */
    default <T> T executeWithoutTransaction(Supplier<T> supplier) {
        return inScope(ignored -> supplier.get());
    }

    /**
     * 在单个共享连接上运行 {@code function} 但不开启事务，把外部提供的 {@code param} 传给它
     * 但不传连接——{@link #executeWithoutTransaction(Supplier)} 的带参版本。无需处理受检异常
     * （无 {@link SQLException} 路径）；借/还语义与
     * {@link #executeWithoutTransaction(Object, ConnectionParamCallback)} 相同。
     *
     * @param param    外部提供的参数，转发给代码体
     * @param function 在共享连接上运行的工作，接收参数
     * @param <T>      结果类型
     * @param <P>      参数类型
     * @return function 的结果
     * @throws io.github.erdsgfc.jforge.core.JForgeException 若无法获取连接，或仓库调用失败
     */
    default <T, P> T executeWithoutTransaction(P param, Function<P, T> function) {
        return inScope(ignored -> function.apply(param));
    }

    /**
     * 在单个共享连接上、无事务地执行 {@code runnable}——
     * {@link #executeWithoutTransaction(ConnectionCallback)} 的无返回值对应物,
     * 用于只需副作用、无需 {@code return null} 的工作体。行为完全一致:借用一个连接
     * 供每次仓库调用共享,保持 auto-commit(无原子性),在 {@code finally} 中归还
     * 连接池({@link SQLException} 包装为 {@link JForgeException})。
     *
     * @param runnable 在共享连接上执行的工作
     * @throws io.github.erdsgfc.jforge.core.JForgeException 若无法获取连接,或工作体抛出 {@link SQLException}
     */
    default void runWithoutTransaction(ConnectionRunnable runnable) {
        executeWithoutTransaction(conn -> {
            runnable.doInConnection(conn);
            return null;
        });
    }

    /**
     * 在单个共享连接上、无事务地执行 {@code runnable},把外部提供的 {@code param}
     * 与共享的 {@link Connection} 一起传入——{@link #runWithoutTransaction(ConnectionRunnable)}
     * 的带参数对应物。行为完全一致:借用一个连接、保持 auto-commit(无原子性)、
     * 在 {@code finally} 中归还连接池;{@link SQLException} 包装为
     * {@link JForgeException}。
     *
     * @param param    外部提供的参数,转发给工作体
     * @param runnable 在共享连接上执行的工作
     * @param <P>      参数类型
     * @throws io.github.erdsgfc.jforge.core.JForgeException 若无法获取连接,或工作体抛出 {@link SQLException}
     */
    default <P> void runWithoutTransaction(P param, ConnectionParamRunnable<P> runnable) {
        executeWithoutTransaction(conn -> {
            runnable.doInConnection(conn, param);
            return null;
        });
    }

    /**
     * 在单个共享连接上、无事务地执行 {@code runnable}——无参数、无连接的
     * {@link #runWithoutTransaction(ConnectionRunnable)} 对应物,用于只调用仓库方法、
     * 从不直接接触连接的工作体。工作体无需受检异常处理:没有 {@code Connection} 就没有
     * 裸 JDBC 路径,只会从仓库调用逃出非受检的 {@link io.github.erdsgfc.jforge.core.JForgeException}。
     * 其余行为一致:借用一个连接供每次仓库调用共享,保持 auto-commit(无原子性),
     * 在 {@code finally} 中归还连接池。
     *
     * @param runnable 在共享连接上执行的工作
     * @throws io.github.erdsgfc.jforge.core.JForgeException 若无法获取连接,或仓库调用失败
     */
    default void runWithoutTransaction(Runnable runnable) {
        executeWithoutTransaction(ignored -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 在单个共享连接上、无事务地执行 {@code consumer},把外部提供的 {@code param}
     * 传入但不暴露连接、无返回值——{@link #runWithoutTransaction(ConnectionRunnable)}
     * 的带参数、无连接对应物。无需受检异常处理(没有 {@link SQLException} 路径);
     * 借用/归还语义与
     * {@link #runWithoutTransaction(Object, ConnectionParamRunnable)} 一致。
     *
     * @param param    外部提供的参数,转发给工作体
     * @param consumer 在共享连接上执行的工作,接收参数
     * @param <P>      参数类型
     * @throws io.github.erdsgfc.jforge.core.JForgeException 若无法获取连接,或仓库调用失败
     */
    default <P> void runWithoutTransaction(P param, Consumer<P> consumer) {
        executeWithoutTransaction(ignored -> {
            consumer.accept(param);
            return null;
        });
    }

    // ---- 私有核心:每个族只有一处生命周期逻辑 --------------------------------

    /**
     * 共享的事务生命周期——每个 {@code execute}/{@code run} 变体背后唯一的提交/回滚
     * 语义实现:开启事务、执行工作体,然后提交——或当工作体(或外层 Spring 事务)把事务
     * 标记为 rollback-only、或工作体/提交本身抛异常时回滚。工作体的 {@link SQLException}
     * 包装为 {@link JForgeException};提交失败后跟一次静默回滚,以免掩盖原始异常。
     *
     * @param callback 事务工作,接收事务绑定连接
     * @param <T>      回调的返回类型
     * @return 回调的结果
     * @throws JForgeException 若事务无法开启、提交或回滚,或回调抛出 SQLException
     */
    private <T> T inTransaction(ConnectionCallback<T> callback) {
        Connection conn = beginTransaction();
        try {
            T result = callback.doInConnection(conn);
            if (isRollbackOnly()) {
                // 回调(或外层 Spring 事务)把事务标记为 rollback-only 以在不抛出异常的情况下
                // 中止:回滚,但仍正常返回回调的结果。
                rollback();
            } else {
                commit();
            }
            return result;
        } catch (SQLException e) {
            // 包装回调的 JDBC 失败,与 ORM 的"无受检异常"契约一致;保留 JDBC 错误消息作为上下文。
            rollbackQuietly();
            throw new JForgeException(
                    JForgeException.Code.SQL,
                    "Transaction failed" + (e.getMessage() != null ? ": " + e.getMessage() : ""), e);
        } catch (RuntimeException | Error ex) {
            // 回滚部分执行的工作体;提交失败时连接已释放,静默回滚可避免用次要的
            // "无活动事务"错误掩盖原始异常。
            rollbackQuietly();
            throw ex;
        }
    }

    /**
     * 共享的连接作用域生命周期——每个 {@code executeWithoutTransaction}/
     * {@code runWithoutTransaction} 变体背后唯一的借用/归还语义实现:借用一个连接、
     * 执行工作体,并在 {@code finally} 中归还连接池——无论成功还是失败。auto-commit
     * 保持开启(无原子性);工作体的 {@link SQLException} 包装为 {@link JForgeException}。
     *
     * @param callback 在共享连接上执行的工作
     * @param <T>      回调的返回类型
     * @return 回调的结果
     * @throws JForgeException 若无法获取连接,或回调抛出 {@link SQLException}
     */
    private <T> T inScope(ConnectionCallback<T> callback) {
        Connection conn = beginConnectionScope();
        try {
            return callback.doInConnection(conn);
        } catch (SQLException e) {
            // 与 execute() 契约一致:回调的 JDBC 失败包装为 JForgeException。
            // 作用域连接仍由 finally 块归还连接池。
            throw new JForgeException(
                    JForgeException.Code.SQL,
                    "Connection scope failed" + (e.getMessage() != null ? ": " + e.getMessage() : ""), e);
        } finally {
            endConnectionScope();
        }
    }

    /**
     * 回滚当前活动事务,吞掉"无事务"失败。供 {@link #inTransaction} 使用,使提交失败
     * 不会掩盖主错误。
     */
    private void rollbackQuietly() {
        try {
            rollback();
        } catch (RuntimeException ignored) {
            // 尽力而为:提交失败路径已清空线程状态。
        }
    }
}
