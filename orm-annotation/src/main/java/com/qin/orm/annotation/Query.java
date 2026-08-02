package com.qin.orm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a custom query on a {@link Dao} repository method.
 *
 * <p>SQL uses {@code :name} placeholders bound to method parameters annotated with
 * {@link Bind}. The generated implementation maps the result based on the method's
 * return type: a {@code @Table} entity interface (by column name), a record/DTO
 * (by component order), a single value, or an update/delete count.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Query {

    /** The SQL statement with {@code :name} placeholders. */
    String value();
}
