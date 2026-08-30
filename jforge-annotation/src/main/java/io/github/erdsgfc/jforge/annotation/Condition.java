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
 * List<UserEntity> findByAgeAndName(@Condition(op = Op.GT) Integer age, String name);
 * // → WHERE 1=1 AND age > ? AND user_name = ?
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.FIELD})
public @interface Condition {

    /**
     * 实体字段名（getter 名）。空字符串（默认值）表示"与参数名/条件对象字段名相同"。
     */
    String value() default "";

    /** 条件操作符（默认等于）。 */
    Op op() default Op.EQ;

    /**
     * 直接使用原生 SQL 片段作为条件（替代 {@link #value()}/{@link #op()} 的
     * "字段 + 操作符"拼装）。片段中的 {@code ?} 占位符绑定该参数的值；无 {@code ?}
     * 则为纯常量条件（参数不绑定，仅用于 {@code @Nullable}/{@code Optional} 的
     * 跳过控制）。
     *
     * <pre>{@code
     * @Select
     * List<UserEntity> findActive(@Condition(rawSql = "status = 'active'") String ignored);
     * // → WHERE status = 'active'
     * }</pre>
     */
    String rawSql() default "";
}
