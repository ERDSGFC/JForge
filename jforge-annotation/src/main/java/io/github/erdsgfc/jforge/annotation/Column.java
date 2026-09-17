package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把一个实体属性映射到数据库列。标在实体接口的 <b>getter 方法</b>上（实体是接口，
 * 属性以无参 getter 声明）；不标注时按 {@code @JForgeConfig.naming} 策略由方法名推断列名。
 *
 * <pre>{@code
 * @Column(name = "user_name", comment = "用户姓名")
 * String name();
 * }</pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Column {

    /**
     * 数据库列名（必填）。
     *
     * @return 列名
     */
    String name();

    /**
     * 列备注——纯元数据，供将来的 DDL/文档生成消费；
     * <b>不影响</b>任何生成的 SQL（生成的语句只含列名与占位符，不带注释）。
     *
     * @return 数据库列备注；未填写时为空串
     */
    String comment() default "";

    /**
     * 写入策略——控制列参与 INSERT（save）与 UPDATE SET（update）的组合，
     * 例如 {@code @Column(name = "created_at", write = WritePolicy.INSERT_ONLY)}。
     * 默认 {@link WritePolicy#BOTH}。
     *
     * @return 写入策略
     */
    WritePolicy write() default WritePolicy.BOTH;
}
