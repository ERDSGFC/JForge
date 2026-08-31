package io.github.erdsgfc.jforge.infer;

import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.JForgeConfig;
import io.github.erdsgfc.jforge.annotation.NamingStrategy;

/**
 * 表名推断策略验证实体：{@code tableNaming = NONE}（类型级配置）→ 无 {@code @Table}
 * 时表名 = 实体简单名原样（{@code ExactNameEntity}），而非默认的 snake_case。
 */
@JForgeConfig(tableNaming = NamingStrategy.NONE)
public interface ExactNameEntity {

    /** 数据库生成的主键（BIGSERIAL）。 */
    @Id
    @GeneratedValue
    Long id();

    ExactNameEntity id(Long id);

    /** 名称。 */
    String name();

    ExactNameEntity name(String name);
}
