package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注声明式方法参数或条件对象字段，指定该参数的 WHERE 条件：
 * {@link #value()} 指定实体字段名（缺省时按参数名推断字段），{@link #op()} 指定操作符。
 * 数组或 {@code Iterable} 集合参数支持 {@link Op#EQ}（生成 {@code IN}）和
 * {@link Op#NE}（生成 {@code NOT IN}）；空集合分别生成恒假 {@code 1 = 0} 和恒真
 * {@code 1 = 1}。标量参数的 {@link Op#NE} 仍生成 {@code <>}。
 * 在 {@link Query} 方法中，{@link #value()} 必须显式填写并表示原生 SQL 列名，
 * 不再按实体字段名推断；其他声明式 API 仍保留按参数/字段名推断。参数是否参与动态拼接由 JSpecify {@code @Nullable} 决定——标注了 {@code @Nullable}
 * 的参数在运行时为 {@code null} 时跳过该条件。
 *
 * <pre>{@code
 * @Select
 * List<UserEntity> findByAgeAndName(@Condition(op = Op.GT) Integer age, String name);
 * // → WHERE 1=1 AND age > ? AND user_name = ?
 * }</pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface Condition {

    /**
     * 实体字段名（getter 名）。空字符串（默认值）表示"与参数名/条件对象字段名相同"。
     */
    String value() default "";

    /**
     * 条件操作符（默认等于）。数组/集合参数仅支持 {@link Op#EQ} 和 {@link Op#NE}，
     * 分别对应 {@code IN}/{@code NOT IN}。
     */
    Op op() default Op.EQ;

    /**
     * 条件字段所属实体。默认的 {@code void.class} 表示当前仓库的宿主实体；指定其他实体时，
     * 该实体必须由当前 {@link Select} 方法上的 {@link Join} 引入。处理器据此解析列名并添加
     * 表限定符，避免多表查询中的同名列歧义。
     *
     * @return 条件字段所属实体，或 {@code void.class}
     */
    Class<?> entity() default void.class;

    /**
     * 直接使用原生 SQL 片段作为条件（替代 {@link #value()}/{@link #op()} 的
     * "字段 + 操作符"拼装）。标量参数支持一个 {@code ?} 占位符；class/record
     * 参数支持多个命名 {@code :fieldName} 占位符，字段只解析当前类型直接声明的字段，
     * 不递归，且按占位符出现顺序绑定。无占位符则为纯常量条件（参数不绑定，仅用于
     * {@code @Nullable}/{@code Optional} 的跳过控制）。
     *
     * <pre>{@code
     * @Select
     * List<UserEntity> findActive(@Condition(rawSql = "status = 'active'") String ignored);
     * // → WHERE status = 'active'
     * }</pre>
     */
    String rawSql() default "";

    /**
     * rawSql 使用 class/record 多参数对象时，是否要求该类型标注 {@link JForgeSql}。
     * 标量参数不受此属性影响。
     */
    boolean requireJForgeSql() default true;
}
