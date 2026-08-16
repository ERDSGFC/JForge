package io.github.erdsgfc.jforge.inherit;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;

/**
 * 实体父接口：声明公共属性（id、name）。父接口不需要 {@code @Table}——
 * 表名由标注了 {@code @Table} 的子接口提供；父接口属性与子接口属性一起映射为列，
 * 父接口属性在前（列顺序 = 继承层次顺序）。
 *
 * <p>注意：父接口的 builder setter 返回父接口类型，链式调用在父 setter 处中断
 * （返回类型收窄为父接口），分步调用不受影响。</p>
 */
public interface BaseEntity {

    /** 数据库生成的主键（BIGSERIAL）。 */
    @Id
    @GeneratedValue
    Long id();

    BaseEntity id(Long id);

    /** 用户显示名，映射到 {@code user_name} 列。 */
    @Column(name = "user_name")
    String name();

    BaseEntity name(String name);
}
