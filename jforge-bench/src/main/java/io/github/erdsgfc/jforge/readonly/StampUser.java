package io.github.erdsgfc.jforge.readonly;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;
import io.github.erdsgfc.jforge.annotation.WritePolicy;

import java.time.LocalDateTime;

/**
 * 四种列写入策略的演示实体：
 * <ul>
 *   <li>{@code created_at}——纯只读（抽象 getter 无 setter 无 default，数据库 DEFAULT 维护）；</li>
 *   <li>{@code inserted_at}——default + {@link WritePolicy#INSERT_ONLY}：save 填一次，update 不触碰；</li>
 *   <li>{@code updated_at}——default + 默认 {@link WritePolicy#BOTH}：save 与 update 都自动刷新；</li>
 *   <li>{@code last_seen_at}——default + {@link WritePolicy#UPDATE_ONLY}：仅 update 刷新，INSERT 不含。</li>
 * </ul>
 */
@Table(name = "stamp_users")
public interface StampUser {

    /** 数据库生成的主键（BIGSERIAL）。 */
    @Id
    @GeneratedValue
    Long id();

    StampUser id(Long id);

    /** 用户显示名，映射到 {@code user_name} 列。 */
    @Column(name = "user_name")
    String name();

    StampUser name(String name);

    /** 用户年龄（岁）。 */
    Integer age();

    StampUser age(Integer age);

    /** 创建时间——纯只读：由数据库默认值（{@code DEFAULT CURRENT_TIMESTAMP}）填充。 */
    @Column(name = "created_at")
    LocalDateTime createdAt();

    /** 插入时间——仅 save 写入一次，update 不触碰。 */
    @Column(name = "inserted_at", write = WritePolicy.INSERT_ONLY)
    default LocalDateTime insertedAt() {
        return LocalDateTime.now();
    }

    /** 更新时间——save 与 update 都自动刷新。 */
    @Column(name = "updated_at")
    default LocalDateTime updatedAt() {
        return LocalDateTime.now();
    }

    /** 最近活跃时间——仅 update 刷新，INSERT 不含（save 后为 null）。 */
    @Column(name = "last_seen_at", write = WritePolicy.UPDATE_ONLY)
    default LocalDateTime lastSeenAt() {
        return LocalDateTime.now();
    }
}
