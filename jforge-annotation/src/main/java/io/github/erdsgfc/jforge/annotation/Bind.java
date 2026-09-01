package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 将仓库方法参数绑定到 {@link Query} 中的命名 {@code :placeholder}。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Bind {

    /** SQL 中的占位符名称（不含前导冒号）。 */
    String value();

    /**
     * 可选的绑定转换器：挂上后该参数的绑定从"按声明类型选 {@code setXxx}"改为
     * {@code ps.setObject(i, CONV.toDatabase(param), CONV.sqlType())}。典型场景：
     * <ul>
     *   <li><strong>按转换列查询</strong>——实体列经 {@code @Convert} 以转换后表示存库
     *       （如 {@code LocalDate} → VARCHAR 文本），查询参数必须经同一转换器生成相同
     *       表示才能命中，否则 DB 侧类型/表示不匹配；</li>
     *   <li>需要显式 SQL 类型的绑定上下文（由 {@link JForgeConverter#sqlType()} 决定）。</li>
     * </ul>
     * 默认哨兵 {@code NoConverter} 表示不挂转换器（参数按声明类型绑定）。
     */
    Class<? extends JForgeConverter<?>> converter() default NoConverter.class;
}
