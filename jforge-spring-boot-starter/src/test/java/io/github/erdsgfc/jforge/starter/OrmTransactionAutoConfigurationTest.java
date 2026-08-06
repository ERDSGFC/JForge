package io.github.erdsgfc.jforge.starter;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the auto-configuration wiring: with a {@link PlatformTransactionManager}
 * bean present, {@link OrmTransactionAutoConfiguration} registers a
 * {@link SpringTransactionManager} and installs it as the global ORM transaction
 * manager; without one, nothing is registered.
 */
class OrmTransactionAutoConfigurationTest {

    /** Fresh in-memory H2 data source per runner start. */
    private DataSource h2DataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_auto;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        return new HikariDataSource(config);
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OrmTransactionAutoConfiguration.class))
            .withBean(PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(h2DataSource()));

    @Test
    void registersSpringTransactionManagerAsGlobalManager() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SpringTransactionManager.class);
            SpringTransactionManager bean = context.getBean(SpringTransactionManager.class);
            // afterSingletonsInstantiated must have swapped the global ORM manager.
            assertThat(io.github.erdsgfc.jforge.TransactionManager.current()).isSameAs(bean);
        });
    }

    @Test
    void noOpWithoutPlatformTransactionManager() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OrmTransactionAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SpringTransactionManager.class);
                });
    }

    @Test
    void autoConfigurationDiscoveredViaImportsFile() {
        // Proves the META-INF/spring/...AutoConfiguration.imports file is packaged and
        // loadable, so a real @SpringBootApplication picks up the configuration.
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
                .getCandidates();
        assertThat(candidates).contains("io.github.erdsgfc.jforge.starter.OrmTransactionAutoConfiguration");
    }
}