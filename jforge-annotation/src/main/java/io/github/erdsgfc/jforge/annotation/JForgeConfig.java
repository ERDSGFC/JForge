package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Per-package / per-repository configuration for the annotation processor.
 *
 * <p>May be placed either on a {@code package-info.java} (applies to every entity and
 * repository in the package) or directly on a {@code @Table} entity / {@code @Dao}
 * repository interface (applies to that element only, overriding the package
 * configuration).  Unconfigured options fall back to the defaults documented below.</p>
 *
 * <pre>{@code
 * // 包级（package-info.java）
 * @JForgeConfig(dialect = Dialect.POSTGRESQL,
 *               naming = NamingStrategy.CAMEL_TO_SNAKE,
 *               generatedPackage = "com.example.data.generated",
 *               implSuffix = "Impl",
 *               springBeans = true)
 * package com.example.data;
 *
 * // 或元素级（直接标在接口上，覆盖包级配置）
 * @JForgeConfig(springBeans = true)
 * public interface UserRepository extends BaseRepository<UserEntity, Long> { ... }
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
}
