package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注一个仓库接口。注解处理器在编译期生成具体的实现类：
 * 继承自 {@code io.github.erdsgfc.jforge.core.BaseRepository} 的 CRUD 方法，
 * 以及所有标注了 {@link Query} 的方法。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Dao {

}
