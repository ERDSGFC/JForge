package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式删除——无需手写 SQL，处理器按参数自动构造 {@code DELETE FROM t WHERE ...}：
 *
 * <ul>
 *   <li>{@link Condition} 参数 / {@link Where} 条件对象 → WHERE 条件（动态语义与
 *       {@code @Select} 完全一致：{@code @Nullable} 跳过、{@code Optional} 空 →
 *       {@code IS NULL}、条件对象括号分组）；</li>
 *   <li>返回影响行数（{@code int}/{@code long}/{@code boolean}）。</li>
 * </ul>
 *
 * <pre>{@code
 * @Delete
 * int deleteById(@Condition Long id);
 * // → DELETE FROM users WHERE id = ?
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Delete {
}
