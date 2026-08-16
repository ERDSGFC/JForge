package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Global configuration for the annotation processor — place it <em>anywhere</em>
 * (a {@code package-info.java}, an application class, a repository interface, ...)
 * and it controls every entity and repository in the compilation.
 *
 * <p>All {@code @JForgeConfig} occurrences are merged into one global
 * configuration: an option is taken from the occurrence that sets it to a
 * non-default value; two occurrences setting the same option to different
 * non-default values are a compile error (the conflict is reported). Unconfigured
 * options fall back to the defaults documented below. Per-repository granularity
 * is handled by dedicated annotations instead (e.g. {@link BatchSize}).</p>
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

    /** SQL dialect used for generated statements (default {@link Dialect#POSTGRESQL}). */
    Dialect dialect() default Dialect.POSTGRESQL;

    /**
     * Names the generated implementation classes are placed in.
     * An empty string (the default) means "same package as the source interface".
     */
    String generatedPackage() default "";

    /** Suffix appended to the simple name of a generated implementation class (default {@code "_Impl"}). */
    String implSuffix() default "_Impl";

    /** Column-name inference strategy used when no {@code @Column} is present (default {@link NamingStrategy#NONE}). */
    NamingStrategy naming() default NamingStrategy.NONE;

    /**
     * Whether generated repository implementation classes are annotated as Spring
     * {@code @Repository} beans with an {@code @Autowired} constructor taking the
     * {@code DataSource}, so Spring Boot component scanning auto-registers them in
     * the application context. Requires the consuming module to have Spring on the
     * classpath. Default {@code false}.
     */
    boolean springBeans() default false;

    /**
     * JDBC batch size for the generated {@code save(List<T>)} method. A positive
     * value enables {@code PreparedStatement.addBatch()/executeBatch()} in chunks
     * of that size, so a batch insert is flushed every {@code batchSize} rows with
     * a single network round-trip each; generated ids are written back to the
     * entities from the batch's generated-keys result set.
     *
     * <p>{@code 50} is the default. {@code 0} disables batching: rows are
     * inserted one by one, but — unlike earlier versions — still on a single
     * shared connection.</p>
     *
     * <p>Overridable per repository with {@link BatchSize} on the repository
     * interface, and per method with {@link BatchSize} on a redeclared
     * {@code save(List<T>)}. Generated-key batching requires driver support for
     * {@code RETURN_GENERATED_KEYS} with {@code executeBatch} (H2 and PostgreSQL
     * support it; some drivers, e.g. older MySQL Connector/J, return keys only for
     * the last statement of a batch).</p>
     */
    int batchSize() default 50;

    /**
     * Whether the generated repository implementation emits DEBUG/WARN SQL logging
     * (an SLF4J {@code Logger} field plus a {@code log.debug(...)} per SQL statement
     * and {@code log.warn(...)} on failure). Default {@code false} — the generated
     * code carries zero logging overhead unless this is enabled, preserving the
     * "equivalent to hand-written JDBC" guarantee.
     */
    boolean logSql() default false;
}
