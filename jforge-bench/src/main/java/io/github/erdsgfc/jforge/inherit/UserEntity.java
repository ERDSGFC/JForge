package io.github.erdsgfc.jforge.inherit;

import io.github.erdsgfc.jforge.annotation.Table;

/**
 * 继承泛型父接口 {@link BaseEntity} 的实体接口：父接口属性（id/name）与自身属性（age）
 * 一起映射为列，自身属性在前。泛型实参为自身（{@code BaseEntity<UserEntity>}），
 * 父接口的 builder setter 返回 {@code T} 替换后即 {@code UserEntity}，链式调用可用。
 */
@Table(name = "users")
public interface UserEntity extends BaseEntity<UserEntity> {

    /** 用户年龄（岁）。 */
    Integer age();

    UserEntity age(Integer age);
}
