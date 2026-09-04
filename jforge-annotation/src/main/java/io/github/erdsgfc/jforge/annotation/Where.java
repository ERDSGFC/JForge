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
 * <p>在 {@link Query} 方法中，条件对象的每个字段都必须显式标注
 * {@link Condition}、嵌套 {@code @Where} 或合法上下文中的 {@link UpdateSet}；
 * {@link And}/{@link Or} 仅用于连接修饰，不能替代语义注解。Query 中的
 * {@code @Condition.value} 必须显式填写，并表示原生 SQL 列名。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface Where {
    /**
     * 是否强制要求条件对象类型标注 {@link JForgeSql}。
     * <p>默认为 {@code true}。设置为 {@code false} 时，处理器仍会按字段/record
     * 组件展开条件，但允许该类型未标注 {@code @JForgeSql}。根参数和嵌套字段
     * 分别读取各自的 {@code value} 设置。</p>
     */
    boolean value() default true;
}
