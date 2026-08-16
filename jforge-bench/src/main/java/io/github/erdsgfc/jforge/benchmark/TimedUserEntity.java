package io.github.erdsgfc.jforge.benchmark;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;
import io.github.erdsgfc.jforge.annotation.WritePolicy;

import java.time.LocalDateTime;

/**
 * 带时间字段的基准实体：{@code created_at}（INSERT_ONLY，save 填一次）与
 * {@code updated_at}（BOTH，save/update 自动刷新）都由框架自动维护——
 * 与裸 JDBC 侧手动 {@code LocalDateTime.now()} 的对照组对比。
 */
@Table(name = "timed_users")
public interface TimedUserEntity {

    /** 数据库生成的主键（BIGSERIAL）。 */
    @Id
    @GeneratedValue
    Long id();

    TimedUserEntity id(Long id);

    /** 用户显示名，映射到 {@code user_name} 列。 */
    @Column(name = "user_name")
    String name();

    TimedUserEntity name(String name);

    /** 用户年龄（岁）。 */
    Integer age();

    TimedUserEntity age(Integer age);

    /** 创建时间——INSERT_ONLY：save 自动填一次，update 不触碰。 */
    @Column(name = "created_at", write = WritePolicy.INSERT_ONLY)
    default LocalDateTime createdAt() {
        return LocalDateTime.now();
    }

    /** 更新时间——BOTH：save 与 update 都自动刷新。 */
    @Column(name = "updated_at")
    default LocalDateTime updatedAt() {
        return LocalDateTime.now();
    }
}
