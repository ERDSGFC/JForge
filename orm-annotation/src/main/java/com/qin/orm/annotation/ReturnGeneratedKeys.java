package com.qin.orm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On an INSERT {@link Query}, requests {@code RETURN_GENERATED_KEYS} and writes the
 * generated key back into the passed entity's {@code @Id} field.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ReturnGeneratedKeys {
}
