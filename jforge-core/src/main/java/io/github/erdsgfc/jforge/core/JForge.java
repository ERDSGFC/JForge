package io.github.erdsgfc.jforge.core;

import io.github.erdsgfc.jforge.core.generated.Repositories;
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
     * @param dataSource 所有仓库将使用的数据源
     */
    public JForge(DataSource dataSource) {
        this(dataSource, new SimpleTransactionManager());
    }

    /**
     * 使用指定事务管理器创建门面。该管理器只归此门面持有：经 {@link #repository(Class)}
     * 创建仓库时传入生成的实现（构造器注入，无全局状态），不同门面实例的管理器完全隔离。
     *
     * @param dataSource         所有仓库将使用的数据源
     * @param transactionManager 绑定到此门面的事务管理器
     */
    public JForge(DataSource dataSource, TransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
        LOG.info("JForge initialized with TransactionManager {}", transactionManager.getClass().getSimpleName());
    }

    /** 返回绑定到此门面的数据源。 */
    public DataSource dataSource() {
        return dataSource;
    }

    /** 返回此门面安装的事务管理器。 */
    public TransactionManager transactionManager() {
        return transactionManager;
    }

    /**
     * 返回给定类型的（缓存）仓库实例。首次调用经生成的
     * {@link Repositories#create(Class, DataSource, TransactionManager)} 创建，传入本门面的
     * {@link #transactionManager}，使生成的实现以 {@code private final} 字段持有它（利于 JIT，
     * 无每次调用的静态查找）；后续调用返回同一实例。
     *
     * @param type 仓库接口类型
     * @param <T>  仓库类型
     * @return 类型为 {@code T} 的缓存仓库实例
     * @throws IllegalArgumentException 若未为 {@code type} 生成仓库
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
