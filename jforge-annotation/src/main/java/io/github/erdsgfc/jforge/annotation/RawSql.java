package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 在 {@link Query} 的 {@code {:name}} 片段位置插入一段编译期原生 SQL。
 * 标量片段最多包含一个 {@code ?}；class/record 片段可使用直接字段的命名
 * {@code :field} 占位符，字段值仍通过 JDBC 绑定。
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface RawSql {
    /** 原生 SQL 文本。 */
    String value();

    /** 对 class/record 参数是否要求类型标注 {@link JForgeSql}。 */
    boolean requireJForgeSql() default true;
}
