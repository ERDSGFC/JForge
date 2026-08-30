package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注 {@link Update} 方法参数，声明该参数是 {@code SET} 列的值——与 {@link Condition}
 * （WHERE 条件）对称。列名取 {@link #value()}（缺省按参数名），值绑定为
 * {@code SET 列 = ?}：
 *
 * <ul>
 *   <li>参数值为 {@code null}（标注 JSpecify {@code @Nullable} 时）→ 跳过该 SET
 *       （保持列原值）；</li>
 *   <li>{@code Optional}/{@code OptionalInt}/{@code OptionalLong} 参数 →
 *       {@code isEmpty()} 时生成 {@code SET 列 = NULL}（显式置空）、有值时
 *       {@code SET 列 = ?}。</li>
 * </ul>
 *
 * <pre>{@code
 * @Update
 * int updateName(@UpdateSet String name, @Condition Long id);
 * // → UPDATE users SET user_name = ? WHERE id = ?
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface UpdateSet {

    /**
     * 实体字段名（getter 名）。空字符串（默认值）表示"与参数名相同"。
     */
    String value() default "";

    /**
     * 直接使用原生 SQL 片段作为 {@code SET} 表达式（替代 {@code 列 = ?} 的拼装，
     * 如 {@code "score = score + ?"}）。片段中的 {@code ?} 占位符绑定该参数的值；
     * 无 {@code ?} 则为纯常量（参数不绑定）。{@code @Nullable}/{@code Optional}
     * 动态语义保留（null/空时跳过该 SET）。
     *
     * <pre>{@code
     * @Update
     * int incrementScore(@UpdateSet(rawSql = "score = score + ?") Integer increment,
     *         @Condition Long id);
     * // → UPDATE users SET score = score + ? WHERE id = ?
     * }</pre>
     */
    String rawSql() default "";
}
