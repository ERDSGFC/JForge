package io.github.erdsgfc.jforge.starter;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.core.TransactionManager;
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
 * 测试自动配置的装配：存在 {@link PlatformTransactionManager} Bean 时，
 * {@link OrmTransactionAutoConfiguration} 注册 {@link SpringTransactionManager}
 * 并将其安装为全局 ORM 事务管理器；不存在时则不注册任何东西。
 */
class OrmTransactionAutoConfigurationTest {

    /** 每次启动 runner 时新建的内存 H2 数据源。 */
    private DataSource h2DataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_auto;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        return new HikariDataSource(config);
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OrmTransactionAutoConfiguration.class))
            .withBean(PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(h2DataSource()));

    @Test
    void registersSpringTransactionManagerBean() {
        runner.run(context -> {
            // 自动配置必须恰好注册一个 SpringTransactionManager
            // Bean——即注入到生成仓库的管理器。
            assertThat(context).hasSingleBean(SpringTransactionManager.class);
            assertThat(context).hasSingleBean(TransactionManager.class);
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
        // 证明 META-INF/spring/...AutoConfiguration.imports 文件已被打包且可加载，
        // 使真正的 @SpringBootApplication 能够启用该配置。
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
                .getCandidates();
        assertThat(candidates).contains("io.github.erdsgfc.jforge.starter.OrmTransactionAutoConfiguration");
    }
}