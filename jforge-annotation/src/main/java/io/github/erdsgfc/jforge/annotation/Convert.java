package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在实体 <em>getter</em> 方法上，指定该列的自定义 Java ↔ 数据库类型转换器。
 *
 * <p>处理器在编译期读取 {@link JForgeConverter} 实现类并生成转换调用——绑定列值时
 * 先经 {@code converter.toDatabase(...)} 再写入，行映射时经 {@code converter.toEntity(...)}
 * 还原。转换器实现类可为 {@code classpath} 预编译或与实体同批编译（Class 类型属性经
 * {@code MirroredTypeException} 恢复类型镜像，处理器不要求类已加载）。</p>
 *
 * <p>转换列的绑定/读取统一走 {@code setObject/getObject}，不参与 {@code nullable}
 * 判定（null 透传给转换器）；转换器须接受并处理 {@code null}。</p>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Convert {

    /** 转换器实现类（实现 {@link JForgeConverter}，公开无参构造器）。 */
    Class<? extends JForgeConverter<?, ?>> converter();
}
