package io.github.erdsgfc.jforge.pgsql.readonly;

import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/**
 * 只读主键实体（无 id setter）：验证 {@code INSERT ... RETURNING} 生成键回写经
 * 嵌套类强转路径（{@code ((AutoIdPgUser_Impl) entity).id(...)}，nestmates 访问
 * private 填充 setter）在真 PG 上工作。
 */
@Table(name = "pg_auto_id_users")
public interface AutoIdPgUser {

    /** 数据库生成的主键（BIGSERIAL）——无 setter，纯只读。 */
    @Id
    @GeneratedValue
    Long id();

    /** 显示名。 */
    String userName();

    AutoIdPgUser userName(String userName);

    /** 年龄（岁）。 */
    Integer age();

    AutoIdPgUser age(Integer age);
}
