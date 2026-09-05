package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式更新——无需手写 SQL，处理器按参数自动构造 {@code UPDATE}：
 *
 * <ul>
 *   <li>{@link UpdateSet} 参数 → {@code SET 列 = ?}（缺省按参数名映射列；{@code @Nullable}
 *       参数为 {@code null} 时跳过该 SET；{@code Optional} 空时 {@code SET 列 = NULL}）；</li>
 *   <li>{@link Condition} 参数 / {@link Where} 条件对象 → WHERE 条件（动态语义与
 *       {@code @Select} 完全一致）；</li>
 *   <li>返回影响行数（{@code int}/{@code long}/{@code boolean}）。</li>
 * </ul>
 *
 * <pre>{@code
 * @Update
 * int updateNameAndAge(@UpdateSet String name, @UpdateSet Integer age, @Condition Long id);
 * // → UPDATE users SET user_name = ?, age = ? WHERE id = ?
 * }</pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Update {
}
