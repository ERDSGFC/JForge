package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注 {@link Select} 或 {@link Query} 方法的参数，声明该参数是**条件对象**——
 * 处理器递归展开其字段为 WHERE 条件：
 *
 * <ul>
 *   <li>值类型字段（基本/包装/{@code String}/{@code LocalDateTime}/枚举…）→ 单条件
 *       {@code 列 op ?}（字段名经命名策略映射列；{@code @Condition} 可指定字段与操作符；
 *       字段值为 {@code null} 时跳过该条件）；</li>
 *   <li>{@code Optional}/{@code OptionalInt}/{@code OptionalLong} 字段 →
 *       {@code isEmpty()} 时生成 {@code 列 IS NULL}（显式表达空值查询）、有值时生成条件；</li>
 *   <li>自定义类字段（非 JDK 值类型）→ **括号分组**，递归展开为 {@code ( ... )}，
 *       与相邻条件的连接方式由字段上的 {@link And}/{@link Or} 指定（缺省 AND）。</li>
 * </ul>
 *
 * <pre>{@code
 * public class UserCriteria {
 *     String name;                     // user_name = ?（null 跳过）
 *     @Or @Condition(op = Op.GT) Integer age;   // OR age > ?
 *     Optional<String> nickname;       // user_name IS NULL（空）或 user_name = ?（有值）
 *     AddressCriteria address;         // AND (city = ? AND street = ?)
 * }
 *
 * @Select
 * List<UserEntity> findComplex(@Where UserCriteria criteria);
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Where {
}
