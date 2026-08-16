package io.github.erdsgfc.jforge.benchmark;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

import java.time.LocalDateTime;

/**
 * 以接口形式声明的基准实体(生成实现:{@code UserEntity_Impl},仓库 impl 的 private static
 * final 嵌套类)。时间字段为普通可写属性——由用户代码手动维护,
 * 与 {@link TimedUserEntity}(框架自动维护)共用 {@code timed_users} 表直接对照。
 */
@Table(name = "timed_users")
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

    /** 创建时间——手动维护。 */
    @Column(name = "created_at")
    LocalDateTime createdAt();

    UserEntity createdAt(LocalDateTime createdAt);

    /** 更新时间——手动维护。 */
    @Column(name = "updated_at")
    LocalDateTime updatedAt();

    UserEntity updatedAt(LocalDateTime updatedAt);
}
