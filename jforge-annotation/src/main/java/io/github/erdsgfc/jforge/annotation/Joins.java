package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link Join} 的可重复注解容器。业务代码通常直接重复声明 {@code @Join}，无需显式使用本注解。
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Joins {

    /**
     * 当前查询的全部连接声明，顺序即 SQL 中的连接顺序。
     *
     * @return 连接声明
     */
    Join[] value();
}
