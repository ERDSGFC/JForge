package io.github.erdsgfc.jforge.batch;

import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/**
 * 批处理测试实体,映射到 {@code batch_users} 表。本包的
 * {@code package-info} 携带 {@code @JForgeConfig(batchSize = 2)}。
 */
@Table(name = "batch_users")
public interface BatchUser {

    /** 数据库生成的主键(BIGSERIAL)。 */
    @Id
    @GeneratedValue
    Long id();

    BatchUser id(Long id);

    /** 显示名称。 */
    String name();

    BatchUser name(String name);
}