package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 将字段映射到列。缺省时，字段名直接用作列名（camelCase 保持不变）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Column {

    /** 列名。 */
    String name();

    /**
     * 写入策略——控制列参与 INSERT（save）与 UPDATE SET（update）的组合，
     * 例如 {@code @Column(name = "created_at", write = WritePolicy.INSERT_ONLY)}。
     * 默认 {@link WritePolicy#BOTH}。
     */
    WritePolicy write() default WritePolicy.BOTH;
}
