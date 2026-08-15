package io.github.erdsgfc.jforge.starter;

import io.github.erdsgfc.jforge.TransactionManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Auto-configuration that registers {@link SpringTransactionManager} as the
 * {@link TransactionManager} bean injected into generated repositories.
 *
 * <p>Kicks in only when the application has a {@link PlatformTransactionManager}
 * bean (typically the {@code DataSourceTransactionManager} auto-configured by
 * {@code spring-boot-starter-jdbc}). Running after
 * {@code DataSourceTransactionManagerAutoConfiguration} guarantees that bean exists
 * before this configuration registers its wrapper.</p>
 *
 * <p>The wrapper bean is injected by Spring into the constructor of every generated
 * repository ({@code @JForgeConfig(springBeans = true)}), so repository work
 * transparently uses Spring's transaction management — no global state involved.</p>
 *
 * <p>Registered via {@code META-INF/spring/org.springframework.boot.autoconfigure
 * .AutoConfiguration.imports}.</p>
 */
@AutoConfiguration(after = DataSourceTransactionManagerAutoConfiguration.class)
@ConditionalOnClass(TransactionManager.class)
@ConditionalOnBean(PlatformTransactionManager.class)
public class OrmTransactionAutoConfiguration {

    /**
     * Registers the Spring-backed ORM transaction manager, or a user-provided
     * {@link SpringTransactionManager} if one is already defined.
     *
     * @param platformTransactionManager the Spring transaction manager to delegate to
     * @return the wrapper bean, injected by Spring into generated repositories
     */
    @Bean
    @ConditionalOnMissingBean(SpringTransactionManager.class)
    public SpringTransactionManager ormTransactionManager(PlatformTransactionManager platformTransactionManager) {
        return new SpringTransactionManager(platformTransactionManager);
    }
}