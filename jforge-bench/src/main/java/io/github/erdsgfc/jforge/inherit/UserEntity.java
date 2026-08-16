package io.github.erdsgfc.jforge.inherit;

import io.github.erdsgfc.jforge.annotation.Table;

/**
 * 继承 {@link BaseEntity} 的实体接口：父接口属性（id/name）与自身属性（age）
 * 一起映射为列，父接口属性在前。
 */
@Table(name = "users")
public interface UserEntity extends BaseEntity {

    /** 用户年龄（岁）。 */
    Integer age();

    UserEntity age(Integer age);
}
