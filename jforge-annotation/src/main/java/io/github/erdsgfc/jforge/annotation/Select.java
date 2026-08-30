package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式查询——无需手写 SQL，处理器按返回类型和方法参数自动生成 SELECT：
 *
 * <ul>
 *   <li>返回实体/{@code List<实体>} → {@code SELECT <全列> FROM t}，行映射与 CRUD 一致；</li>
 *   <li>返回 record → {@code SELECT <组件列> FROM t}（组件名经命名策略得列名）；</li>
 *   <li>返回标量（{@code long}/{@code int}/{@code boolean}）→ {@code SELECT COUNT(*)}。</li>
 * </ul>
 *
 * <p>方法参数即查询条件，默认等于（{@code col = ?}）；{@link Condition} 可显式指定字段名与
 * 操作符。参数标注 JSpecify {@code @Nullable} 时条件动态拼接——运行时参数为
 * {@code null} 则跳过该条件（非 null 才拼 {@code AND col op ?}）；未标注的参数
 * 始终拼接。</p>
 *
 * <pre>{@code
 * @Select
 * List<UserEntity> findByAge(@Nullable Integer age);            // age 为 null 时查全表
 *
 * @Select
 * List<UserEntity> findByName(String name);                    // WHERE user_name = ?
 *
 * @Select
 * long countByAge(@Condition(op = Op.GT) Integer age);             // SELECT COUNT(*) WHERE age > ?
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Select {
}
