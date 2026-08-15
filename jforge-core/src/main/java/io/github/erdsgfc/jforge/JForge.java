package io.github.erdsgfc.jforge;

import io.github.erdsgfc.jforge.generated.Repositories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一门面：集中设置 {@link DataSource} 与 {@link TransactionManager}，并缓存全部仓库实例。
 *
 * <p>{@code dataSource} 与 {@code transactionManager} 都是 {@code private final}——利于 JIT
 * 常量折叠/内联；仓库经 {@link #repository(Class)} 获取，同一类型每次返回同一实例（缓存）。</p>
 *
 * <p>仓库的创建委托给注解处理器生成的 {@link Repositories}（固定包
 * {@code io.github.erdsgfc.jforge.generated}）：框架 jar 自带的同名空壳占位类会被用户项目
 * target/classes 里处理器生成的真实实现覆盖（Java 类加载优先级：磁盘项目路径高于第三方 jar）。</p>
 */
public final class JForge {

    private static final Logger LOG = LoggerFactory.getLogger(JForge.class);

    private final DataSource dataSource;
    private final TransactionManager transactionManager;
    private final Map<Class<?>, Object> repositories = new ConcurrentHashMap<>();

    /**
     * 使用内置 {@link SimpleTransactionManager} 创建门面。
     *
     * @param dataSource the data source all repositories will use
     */
    public JForge(DataSource dataSource) {
        this(dataSource, new SimpleTransactionManager());
    }

    /**
     * 使用指定事务管理器创建门面。该管理器只归此门面持有：经 {@link #repository(Class)}
     * 创建仓库时传入生成的实现（构造器注入，无全局状态），不同门面实例的管理器完全隔离。
     *
     * @param dataSource         the data source all repositories will use
     * @param transactionManager the transaction manager bound to this facade
     */
    public JForge(DataSource dataSource, TransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
        LOG.info("JForge initialized with TransactionManager {}", transactionManager.getClass().getSimpleName());
    }

    /** Returns the data source bound to this facade. */
    public DataSource dataSource() {
        return dataSource;
    }

    /** Returns the transaction manager installed by this facade. */
    public TransactionManager transactionManager() {
        return transactionManager;
    }

    /**
     * Returns the (cached) repository instance for the given type. The first call creates
     * it via the generated {@link Repositories#create(Class, DataSource, TransactionManager)},
     * passing this facade's {@link #transactionManager} so the generated impl holds it as a
     * {@code private final} field (JIT-friendly, no per-call static lookup); subsequent
     * calls return the same instance.
     *
     * @param type the repository interface type
     * @param <T>  the repository type
     * @return a cached repository instance of type {@code T}
     * @throws IllegalArgumentException if no repository was generated for {@code type}
     */
    @SuppressWarnings("unchecked")
    public <T> T repository(Class<T> type) {
        return (T) repositories.computeIfAbsent(type, t -> {
            Object repo = Repositories.create(t, dataSource, transactionManager);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Created repository for {}", t.getSimpleName());
            }
            return repo;
        });
    }
}
