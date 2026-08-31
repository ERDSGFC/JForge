package io.github.erdsgfc.jforge.pgsql.batch;

import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/**
 * 批处理测试实体：批量 save 走 {@code addBatch()/executeBatch()}（batchSize=2
 * 分块 flush），生成键回写走 JDBC 标准 {@code getGeneratedKeys}（PG 驱动按插入序
 * 返回全部键）——真 PG 上验证批量键回写与 RETURNING 单条路径的并存。
 */
@Table(name = "pg_batch_users")
public interface BatchPgUser {

    /** 数据库生成的主键（BIGSERIAL）。 */
    @Id
    @GeneratedValue
    Long id();

    BatchPgUser id(Long id);

    /** 显示名。 */
    String userName();

    BatchPgUser userName(String userName);
}
