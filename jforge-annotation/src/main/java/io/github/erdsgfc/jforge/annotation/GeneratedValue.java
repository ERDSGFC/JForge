package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a primary-key field as database-generated (e.g. IDENTITY / AUTO_INCREMENT).
 * After insert, the generated key is written back into the entity field.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GeneratedValue {
}
