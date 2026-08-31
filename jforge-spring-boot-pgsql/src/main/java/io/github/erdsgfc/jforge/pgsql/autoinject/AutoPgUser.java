package io.github.erdsgfc.jforge.pgsql.autoinject;

import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/**
 * Spring 自动注入测试实体：{@code springBeans = true}（包级配置）使生成的仓库 impl
 * 标 {@code @Repository} + {@code @Autowired} 构造器，Spring 组件扫描自动注册。
 */
@Table(name = "pg_auto_users")
public interface AutoPgUser {

    /** 数据库生成的主键（BIGSERIAL）。 */
    @Id
    @GeneratedValue
    Long id();

    AutoPgUser id(Long id);

    /** 显示名。 */
    String userName();

    AutoPgUser userName(String userName);
}
