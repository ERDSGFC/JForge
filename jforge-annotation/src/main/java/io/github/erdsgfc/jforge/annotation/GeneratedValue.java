package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 将主键字段标记为数据库生成（例如 IDENTITY / AUTO_INCREMENT）。
 * 插入后，生成的键会被回写到实体字段中。
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface GeneratedValue {
}
