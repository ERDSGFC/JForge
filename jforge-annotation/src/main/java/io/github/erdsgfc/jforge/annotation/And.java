package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注 {@link Where} 条件对象的字段，声明该字段条件与<em>上一条件</em>用
 * {@code AND} 连接（缺省行为，标注与否等价）。用于明确表达，或与 {@link Or} 混排时
 * 保证语义清晰。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface And {
}
