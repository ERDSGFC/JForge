package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注解处理器的全局配置——可以放在<em>任何位置</em>
 * （{@code package-info.java}、应用类、仓库接口……），
 * 它控制编译中的所有实体和仓库。
 *
 * <p>所有 {@code @JForgeConfig} 出现处会被合并为一个全局配置：
 * 某项配置取自将其设为非默认值的那一处；若两处将同一配置项设为不同的非默认值，
 * 则编译报错（冲突会被报告）。未配置的配置项回退到下文记录的默认值。
 * 仓库级别的粒度由专用注解处理（例如 {@link BatchSize}）。</p>
 *
 * <pre>{@code
 * // 任意位置——比如一个全局配置类
 * @JForgeConfig(dialect = Dialect.POSTGRESQL,
 *               naming = NamingStrategy.CAMEL_TO_SNAKE,
 *               implSuffix = "Impl",
 *               springBeans = true)
 * public final class OrmConfiguration { }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.PACKAGE, ElementType.TYPE})
public @interface JForgeConfig {

    /** 生成语句所用的 SQL 方言（默认 {@link Dialect#POSTGRESQL}）。 */
    Dialect dialect() default Dialect.POSTGRESQL;

    /**
     * 生成的实现类所在的包名。
     * 空字符串（默认值）表示"与源接口同包"。
     */
    String generatedPackage() default "";

    /** 追加到生成的实现类简单名后的后缀（默认 {@code "_Impl"}）。 */
    String implSuffix() default "_Impl";

    /** 当没有 {@code @Column} 注解时使用的列名推断策略（默认 {@link NamingStrategy#NONE}）。 */
    NamingStrategy naming() default NamingStrategy.NONE;

    /**
     * 生成的仓库实现类是否标注为 Spring {@code @Repository} bean，
     * 并使用接收 {@code DataSource} 的 {@code @Autowired} 构造器，
     * 从而让 Spring Boot 组件扫描自动将其注册到应用上下文中。
     * 要求使用方模块的 classpath 中包含 Spring。默认 {@code false}。
     */
    boolean springBeans() default false;

    /**
     * 生成的 {@code save(List<T>)} 方法的 JDBC 批处理大小。正值会按该大小分块
     * 启用 {@code PreparedStatement.addBatch()/executeBatch()}，
     * 即每 {@code batchSize} 行 flush 一次批量插入，每次批量仅一次网络往返；
     * 生成的 id 会从批次的 generated-keys 结果集回写到实体中。
     *
     * <p>默认值为 {@code 50}。{@code 0} 表示禁用批处理：行逐条插入，
     * 但与早期版本不同——仍然使用单条共享连接。</p>
     *
     * <p>可按仓库在仓库接口上用 {@link BatchSize} 覆盖，也可按方法在重声明的
     * {@code save(List<T>)} 上用 {@link BatchSize} 覆盖。生成键的批处理要求驱动
     * 支持 {@code executeBatch} 与 {@code RETURN_GENERATED_KEYS} 配合使用（H2 和
     * PostgreSQL 支持；某些驱动，例如旧版 MySQL Connector/J，只返回批次中
     * 最后一条语句的键）。</p>
     */
    int batchSize() default 50;

    /**
     * 生成的仓库实现是否输出 DEBUG/WARN 级别的 SQL 日志
     * （一个 SLF4J {@code Logger} 字段，外加每条 SQL 语句的 {@code log.debug(...)}
     * 和失败时的 {@code log.warn(...)}）。默认 {@code false}——除非启用，
     * 否则生成代码不带任何日志开销，从而保持"与手写 JDBC 等价"的保证。
     */
    boolean logSql() default false;
}
