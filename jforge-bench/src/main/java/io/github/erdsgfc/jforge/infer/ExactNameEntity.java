package io.github.erdsgfc.jforge.infer;

import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.JForgeConfig;
import io.github.erdsgfc.jforge.annotation.NamingStrategy;

/**
 * 表名推断策略验证实体：{@code tableNaming = NONE}（类型级配置）→ 无 {@code @Table}
 * 时表名 = 实体简单名原样（{@code ExactNameEntity}），而非默认的 snake_case。
 *
 * <p>注意：原样名含大写，生成 SQL 会按方言引用符包裹为 {@code "ExactNameEntity"}
 * 并精确匹配，因此测试 DDL 必须同样加引号
 * （{@code CREATE TABLE "ExactNameEntity"}）——无引号 DDL 会被数据库折叠为
 * 小写/大写而匹配失败。见 {@link NamingStrategy#NONE} 的"与 DDL 的契约"。</p>
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
