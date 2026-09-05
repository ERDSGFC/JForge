package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 在 INSERT 类型的 {@link Query} 上，请求 {@code RETURN_GENERATED_KEYS}，
 * 并将生成的键回写到传入实体的 {@code @Id} 字段中。
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface ReturnGeneratedKeys {
}
