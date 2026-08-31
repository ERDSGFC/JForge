package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注解处理器的全局配置——标在{@code package-info.java}（包级）或实体/仓库接口（类型级），
 * 控制编译中所有实体和仓库的生成行为。
 *
 * <p>解析规则（不合并、不冲突报错）：接口上直接标注的配置优先；否则沿包链向上
 * （所在包 → 父包 → …）取最近的、其 {@code package-info} 上标有
 * {@code @JForgeConfig} 的包——放在公共父包即可覆盖其全部子包。
 * 无匹配时回退到各项的默认值。仓库/方法级粒度由专用注解处理（例如 {@link BatchSize}）。</p>
 *
 * <pre>{@code
 * // package-info.java——对本包及其所有子包生效
 * @JForgeConfig(dialect = Dialect.POSTGRESQL,
 *               naming = NamingStrategy.CAMEL_TO_SNAKE,
 *               springBeans = true)
 * package com.example;
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.PACKAGE, ElementType.TYPE})
public @interface JForgeConfig {

    /** 生成语句所用的 SQL 方言（默认 {@link Dialect#POSTGRESQL}）。 */
    Dialect dialect() default Dialect.POSTGRESQL;

    /**
     * 自定义方言实现类的全限定名（实现 {@link DialectSupport}）——框架内置
     * {@link Dialect#POSTGRESQL}/{@link Dialect#MYSQL}/{@link Dialect#SQLITE}/{@link Dialect#H2}
     * 四个实现，其他数据库由用户实现该接口并在编译 classpath 提供
     * （需预编译，不能与主源码同批编译）。空字符串（默认值）时按 {@link #dialect()}
     * 使用内建实现。
     *
     * <p>用字符串而非 {@code Class} 属性：javac 注解处理器读取 Class 类型属性时
     * 即使取默认值也会尝试加载类并抛 {@code MirroredTypeException}。</p>
     */
    String dialectClass() default "";

    /** 追加到生成的实现类简单名后的后缀（默认 {@code "_Impl"}）。 */
    String implSuffix() default "_Impl";

    /** 当没有 {@code @Column} 注解时使用的列名推断策略（默认 {@link NamingStrategy#NONE}）。 */
    NamingStrategy naming() default NamingStrategy.NONE;

    /**
     * 生成的仓库实现类是否标注 Spring {@code @Repository}，并去掉 {@code final}
     * （final 类无法被 CGLIB 代理）；构造器标注 {@code @Autowired}（接收
     * {@code DataSource} 与 {@code TransactionManager}），由 Spring Boot 组件扫描
     * 自动注册为 bean。要求使用方模块的 classpath 包含 Spring。默认 {@code false}。
     */
    boolean springBeans() default false;

    /**
     * 生成的 {@code save(List<T>)} 方法的 JDBC 批处理大小。正值按该大小分块
     * 使用 {@code PreparedStatement.addBatch()/executeBatch()}，每 {@code batchSize}
     * 行 flush 一次批量插入，每次批量仅一次网络往返；生成的键从批次的
     * generated-keys 结果集按插入序回写到实体。
     *
     * <p>默认值为 {@code 50}；{@code 0} 表示禁用批处理——逐条插入，但仍使用单条
     * 共享连接。可按仓库或按方法用 {@link BatchSize} 覆盖。生成键的批处理要求驱动
     * 支持 {@code executeBatch} 与 {@code RETURN_GENERATED_KEYS} 配合（H2 与
     * PostgreSQL 支持；某些驱动，例如旧版 MySQL Connector/J，只返回批次中最后
     * 一条的键）。</p>
     */
    int batchSize() default 50;

    /**
     * 生成的仓库实现是否输出 DEBUG/WARN 级 SQL 日志（SLF4J {@code Logger} 字段 +
     * 每条 SQL 的 {@code log.debug(...)}，失败时 {@code log.warn(...)}）。
     * 默认 {@code false}——关闭时不生成任何日志代码，保持"与手写 JDBC 等效"的零开销。
     */
    boolean logSql() default false;

    /**
     * 列的默认空性（nullability）——决定行映射是否生成 {@code ResultSet.wasNull()}
     * 判断。逐列判定规则：
     * <ul>
     *   <li>基本类型 getter（{@code int}/{@code long}/…）→ 恒非空；</li>
     *   <li>包装类 getter（{@code Integer}/{@code Long}/…）→ 恒可空；</li>
     *   <li>其他类型（{@code String}/{@code LocalDateTime}/…）→ getter 返回类型标注
     *       JSpecify {@code @Nullable} 则可空，否则取本配置的默认值。</li>
     * </ul>
     * 默认 {@code false}（未标注的其他类型列视为非空）。
     */
    boolean columnsNullable() default false;
}
