package com.qin.orm.starter;

import com.qin.orm.TransactionManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Auto-configuration that installs {@link SpringTransactionManager} as the global
 * ORM {@link TransactionManager}.
 *
 * <p>Kicks in only when the application has a {@link PlatformTransactionManager}
 * bean (typically the {@code DataSourceTransactionManager} auto-configured by
 * {@code spring-boot-starter-jdbc}). Running after
 * {@code DataSourceTransactionManagerAutoConfiguration} guarantees that bean exists
 * before this configuration registers its wrapper.</p>
 *
 * <p>Once installed, every repository inheriting {@code TransactionOperations} —
 * {@code beginTransaction/commit/rollback/isTransactionActive/execute} — and every
 * generated {@code TransactionManager.current()} call transparently uses Spring's
 * transaction management. The ORM's own classes are untouched; only the global
 * singleton the generated code routes through is swapped.</p>
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
     * @return the wrapper bean, which installs itself as the global ORM manager once
     *         all singletons are instantiated
     */
    @Bean
    @ConditionalOnMissingBean(SpringTransactionManager.class)
    public SpringTransactionManager ormTransactionManager(PlatformTransactionManager platformTransactionManager) {
        return new SpringTransactionManager(platformTransactionManager);
    }
}