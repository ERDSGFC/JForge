package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Per-package configuration for the annotation processor.
 *
 * <p>Place on a {@code package-info.java} file in the package that contains your
 * {@code @Table} entities and/or {@code @Dao} repositories.  Unconfigured options
 * fall back to the defaults documented below.</p>
 *
 * <pre>{@code
 * @JForgeConfig(dialect = Dialect.POSTGRESQL,
 *               naming = NamingStrategy.CAMEL_TO_SNAKE,
 *               generatedPackage = "com.example.data.generated",
 *               implSuffix = "Impl",
 *               springBeans = true)
 * package com.example.data;
 * import io.github.erdsgfc.jforge.annotation.*;
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PACKAGE)
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
}
