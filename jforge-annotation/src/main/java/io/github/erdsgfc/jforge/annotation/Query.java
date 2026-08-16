package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 在 {@link Dao} 仓库方法上声明一个自定义查询。
 *
 * <p>SQL 使用 {@code :name} 占位符，绑定到标注了 {@link Bind} 的方法参数上。
 * 生成的实现根据方法的返回类型映射结果：{@code @Table} 实体接口（按列名）、
 * record/DTO（按组件顺序）、单个值，或更新/删除的行数。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Query {

    /** 带 {@code :name} 占位符的 SQL 语句。 */
    String value();
}
