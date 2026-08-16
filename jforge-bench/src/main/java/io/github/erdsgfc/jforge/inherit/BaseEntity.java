package io.github.erdsgfc.jforge.inherit;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;

/**
 * 泛型实体父接口（CRTP 自限定模式）：声明公共属性（id、name）。父接口不需要
 * {@code @Table}——表名由标注了 {@code @Table} 的子接口提供；父接口属性与子接口属性
 * 一起映射为列，子接口自身属性在前（列顺序 = 继承层次顺序）。
 *
 * <p>类型参数 {@code T} 必须且只能有一个，且必须是子接口自身（如
 * {@code interface UserEntity extends BaseEntity<UserEntity>}）——builder setter
 * 返回 {@code T}，替换后返回子接口类型，链式调用不会中断。</p>
 */
public interface BaseEntity<T extends BaseEntity<T>> {

    /** 数据库生成的主键（BIGSERIAL）。 */
    @Id
    @GeneratedValue
    Long id();

    T id(Long id);

    /** 用户显示名，映射到 {@code user_name} 列。 */
    @Column(name = "user_name")
    String name();

    T name(String name);
}
