package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 将仓库方法参数绑定到 {@link Query} 中的命名 {@code :placeholder}。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Bind {

    /** SQL 中的占位符名称（不含前导冒号）。 */
    String value();
}
