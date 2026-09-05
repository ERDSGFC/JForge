package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注 {@link Where} 条件对象的字段，声明该字段条件（或括号分组）与<em>上一条件</em>
 * 用 {@code OR} 连接。
 *
 * <pre>{@code
 * public class UserCriteria {
 *     String name;                          // user_name = ?
 *     @Or @Condition(op = Op.GT) Integer age;   // OR age > ?
 *     @Or AddressCriteria address;          // OR (city = ? AND street = ?)
 * }
 * // → WHERE user_name = ? OR age > ? OR (city = ? AND street = ?)
 * }</pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface Or {
}
