package io.github.erdsgfc.jforge.annotation;

/**
 * 数据库方言 SPI：处理器在编译期加载实现，把方言差异直接拼进生成的 SQL。
 * 框架内置 {@link Dialect#POSTGRESQL}/{@link Dialect#MYSQL}/{@link Dialect#SQLITE}/
 * {@link Dialect#H2} 四个实现；其他数据库由用户实现本接口，经
 * {@link JForgeConfig#dialectClass()} 指定。
 *
 * <p><strong>重要</strong>：方言在编译期使用——实现类必须位于编译 classpath
 * （独立模块预编译或先前已编译），不能与主源码同批编译（注解处理期间
 * 同批源码的类尚无 class 文件）。</p>
 */
public interface DialectSupport {

    /** 生成键回写：{@code INSERT ... RETURNING}（单语句拿 id）或 JDBC 标准
     *  {@code getGeneratedKeys}。批量 save 始终走 JDBC 标准（驱动对批量
     *  {@code RETURNING} 结果集的读取差异大）。 */
    boolean supportsReturningKeys();

    /** 自增列的 DDL 类型（未来 schema 生成用），如 {@code "BIGSERIAL"}。 */
    String serialType();

    /** 标识符引用符（保留字列名/表名支持用），如 {@code "\""}。 */
    String quote();

    /** 分页片段（含占位符），如 {@code "LIMIT ? OFFSET ?"}。 */
    String limitClause();

    /** UPSERT 子句（未来 saveOrUpdate 用），如 {@code "ON CONFLICT"}。 */
    String upsertClause();

    /** 布尔字面量，如 {@code "TRUE"}。 */
    String booleanLiteral();

    /**
     * 是否支持 PostgreSQL 风格的 dollar-quote 字符串（{@code $tag$...$tag$}）。
     * 默认不启用，避免自定义方言把普通 {@code $} 文本误判为字符串分隔符。
     */
    default boolean supportsDollarQuotedStrings() {
        return false;
    }

    /**
     * 是否支持 PostgreSQL/H2 风格的 {@code ::} 类型转换操作符。
     * 默认不启用。
     */
    default boolean supportsDoubleColonCast() {
        return false;
    }

    /**
     * 是否支持 PostgreSQL JDBC 使用 {@code ??} 转义字面量 {@code ?} 操作符。
     * 该写法常用于 JSON/JSONB 的键存在性查询；处理器会保留两个字符，交由 JDBC
     * 驱动在发送 SQL 时还原为一个 {@code ?}，并且不会把它们统计为绑定参数。
     * 默认不启用。
     */
    default boolean supportsDoubleQuestionMarkEscape() {
        return false;
    }

    /**
     * 是否支持反引号标识符（{@code `column`}）。默认根据标识符引用符判断；
     * 引用符为反引号的方言无需额外覆写该方法。
     */
    default boolean supportsBacktickQuotedIdentifiers() {
        return "`".equals(quote());
    }
}
