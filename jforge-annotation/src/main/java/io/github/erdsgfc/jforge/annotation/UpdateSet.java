package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个修改字段——{@code UPDATE} 的 {@code SET} 列值来源（与 {@link Condition}
 * WHERE 条件对称）。两处使用：
 *
 * <ul>
 *   <li><strong>{@link Update} 方法参数</strong>：参数即 {@code SET} 值，列名取
 *       {@link #value()}（缺省按参数名）；</li>
 *   <li><strong>{@code @Where} 条件对象字段</strong>：字段即 {@code SET} 值（区别于
 *       默认的 WHERE 条件字段）——一个条件对象同时携带修改字段与条件字段，供
 *       {@code @Update ... SET ... WHERE ...} 参数化。
 *       <pre>{@code
 *       @JForgeSql
 *       public class UserUpdate {
 *           @UpdateSet String name;          // → SET user_name = ?
 *           @UpdateSet @Nullable Integer age; // → SET age = ?（null 跳过）
 *           String nickname;                 // → WHERE user_name = ?（条件）
 *           Long id;                         // → WHERE id = ?
 *       }
 *       @Update
 *       int updateById(@Where UserUpdate update);
 *       // → UPDATE users SET user_name = ?, age = ? WHERE user_name = ? AND id = ?
 *       }</pre></li>
 * </ul>
 *
 * 动态语义：字段/参数值可空（JSpecify 空性）为 {@code null} → 跳过该 SET（保持列原值）；
 * {@code Optional} 空 → {@code SET 列 = NULL}（显式置空）、有值 → {@code SET 列 = ?}。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.RECORD_COMPONENT})
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
