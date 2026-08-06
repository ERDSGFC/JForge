package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository interface. The annotation processor generates a concrete
 * implementation class at compile time: CRUD methods inherited from
 * {@code io.github.erdsgfc.jforge.core.BaseRepository} plus any {@link Query}-annotated methods.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Dao {

}
