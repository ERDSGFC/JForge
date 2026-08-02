package com.qin.orm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository interface. The annotation processor generates a concrete
 * implementation class at compile time: CRUD methods inherited from
 * {@code com.qin.orm.core.BaseRepository} plus any {@link Query}-annotated methods.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Dao {

}
