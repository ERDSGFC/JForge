package com.qin.orm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository method as transactional: the generated implementation wraps
 * the body in {@code TransactionManager.begin/commit/rollback}.
 *
 * <p>When placed on the {@code @Dao} interface itself all methods become
 * transactional.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Transactional {
}