package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注 {@link Select} 方法参数，指定该参数的 WHERE 条件：
 * {@link #value()} 指定实体字段名（缺省时按参数名推断字段），{@link #op()} 指定操作符。
 * 参数是否参与动态拼接由 JSpecify {@code @Nullable} 决定——标注了 {@code @Nullable}
 * 的参数在运行时为 {@code null} 时跳过该条件。
 *
 * <pre>{@code
 * @Select
 * List<UserEntity> findByAgeAndName(@Where(op = Op.GT) Integer age, String name);
 * // → WHERE 1=1 AND age > ? AND user_name = ?
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Where {

    /**
     * 实体字段名（getter 名）。空字符串（默认值）表示"与参数名相同"。
     */
    String value() default "";

    /** 条件操作符（默认等于）。 */
    Op op() default Op.EQ;
}
