package io.github.erdsgfc.jforge.annotation;

/**
 * 数据库方言 SPI：处理器在编译期加载实现，把方言差异直接拼进生成的 SQL。
 * 框架内置 {@link Dialect#POSTGRESQL} 与 {@link Dialect#MYSQL} 两个实现；
 * 其他数据库由用户实现本接口，经 {@link JForgeConfig#dialectClass()} 指定。
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
}
