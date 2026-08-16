package io.github.erdsgfc.jforge.benchmark;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/** 以接口形式声明的基准实体(生成实现:{@code UserEntity_Impl},仓库 impl 的 private static final 嵌套类)。 */
@Table(name = "users")
public interface UserEntity {

    @Id
    @GeneratedValue
    Long id();

    UserEntity id(Long id);

    @Column(name = "user_name")
    String name();

    UserEntity name(String name);

    Integer age();

    UserEntity age(Integer age);
}
