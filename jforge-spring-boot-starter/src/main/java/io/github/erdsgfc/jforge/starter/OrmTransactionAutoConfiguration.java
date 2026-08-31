package io.github.erdsgfc.jforge.starter;

import io.github.erdsgfc.jforge.core.TransactionManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 自动配置：将 {@link SpringTransactionManager} 注册为注入到生成仓库的
 * {@link TransactionManager} Bean。
 *
 * <p>仅在应用存在 {@link PlatformTransactionManager} Bean（通常是由
 * {@code spring-boot-starter-jdbc} 自动配置的 {@code DataSourceTransactionManager}）
 * 时生效。在 {@code DataSourceTransactionManagerAutoConfiguration} 之后运行，
 * 保证本配置注册包装器之前该 Bean 已存在。</p>
 *
 * <p>包装器 Bean 由 Spring 注入每个生成仓库的构造器
 * （{@code @JForgeConfig(springBeans = true)}），因此仓库工作透明地使用 Spring 的
 * 事务管理——不涉及全局状态。</p>
 *
 * <p>通过 {@code META-INF/spring/org.springframework.boot.autoconfigure
 * .AutoConfiguration.imports} 注册。</p>
 */
@AutoConfiguration(after = DataSourceTransactionManagerAutoConfiguration.class)
@ConditionalOnClass(TransactionManager.class)
@ConditionalOnBean(PlatformTransactionManager.class)
public class OrmTransactionAutoConfiguration {

    /**
     * 注册基于 Spring 的 ORM 事务管理器；若已有用户提供的 {@link SpringTransactionManager}
     * 则不再注册。
     *
     * @param platformTransactionManager 要委托的 Spring 事务管理器
     * @return 包装器 Bean，由 Spring 注入生成的仓库
     */
    @Bean
    @ConditionalOnMissingBean(SpringTransactionManager.class)
    public SpringTransactionManager ormTransactionManager(PlatformTransactionManager platformTransactionManager) {
        return new SpringTransactionManager(platformTransactionManager);
    }
}