package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标记实体的主键字段。 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Id {
}
