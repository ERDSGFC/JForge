package io.github.erdsgfc.jforge;

/**
 * ORM 在连接、SQL、事务、映射和配置错误时抛出的运行时异常。
 *
 * <p>携带粗粒度的错误 {@link Code} 分类及可选的 SQL 语句，使调用方可以编程方式对失败分类
 * （{@link #code()}）、读取出错的 SQL（{@link #sql()}）并获得自包含的消息——生成的仓库代码
 * 会把操作、表名和 SQL 嵌入消息中，因此无需从 {@link #getCause()} 链条里挖掘根因即可
 * 弄清失败原因。</p>
 */
public class JForgeException extends RuntimeException {

    /** 供编程式处理使用的粗粒度错误分类。 */
    public enum Code {
        /** 连接获取/释放失败。 */
        CONNECTION,
        /** SQL 语句执行失败。 */
        SQL,
        /** 事务开启/提交/回滚失败。 */
        TRANSACTION,
        /** 实体/行映射失败。 */
        MAPPING,
        /** 配置/校验错误。 */
        CONFIGURATION
    }

    private final Code code;
    private final String sql;

    /**
     * 以默认的 {@link Code#SQL} 分类创建异常。
     *
     * @param message 错误消息
     */
    public JForgeException(String message) {
        this(Code.SQL, message, null, null);
    }

    /**
     * 以默认的 {@link Code#SQL} 分类和原因创建异常。
     *
     * @param message 错误消息
     * @param cause   底层原因
     */
    public JForgeException(String message, Throwable cause) {
        this(Code.SQL, message, null, cause);
    }

    /**
     * 以显式分类且无原因创建异常。
     *
     * @param code    错误分类
     * @param message 错误消息
     */
    public JForgeException(Code code, String message) {
        this(code, message, null, null);
    }

    /**
     * 以显式分类和原因创建异常。
     *
     * @param code    错误分类
     * @param message 错误消息
     * @param cause   底层原因
     */
    public JForgeException(Code code, String message, Throwable cause) {
        this(code, message, null, cause);
    }

    /**
     * 以显式分类、SQL 上下文和原因创建异常。
     *
     * @param code    错误分类
     * @param message 错误消息
     * @param sql     执行失败的 SQL 语句，或 {@code null}
     * @param cause   底层原因
     */
    public JForgeException(Code code, String message, String sql, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.sql = sql;
    }

    /** 返回粗粒度的错误分类。 */
    public Code code() {
        return code;
    }

    /** 返回执行失败的 SQL 语句，不适用时为 {@code null}。 */
    public String sql() {
        return sql;
    }
}
