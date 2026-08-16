package io.github.erdsgfc.jforge.starter;

import org.springframework.transaction.annotation.Transactional;

/**
 * 一个普通服务 Bean，其 {@code @Transactional} 方法在 Spring 声明式事务控制下
 * 执行仓库写入。它在测试配置中注册为 {@code @Bean}，并由 Spring 的事务拦截器代理。
 * 内部使用的仓库会加入声明式事务，因为生成代码通过其注入的
 * {@link TransactionManager} → {@link SpringTransactionManager} →
 * {@code DataSourceUtils.getConnection} 获取连接。
 */
public class SpringTxService {

    private final TestUserRepository repo;

    /**
     * @param repo 用于执行写入的仓库
     */
    public SpringTxService(TestUserRepository repo) {
        this.repo = repo;
    }

    /** 在 {@code @Transactional} 边界内保存一行；返回时提交。 */
    @Transactional
    public void commitWork() {
        repo.save(repo.createEntity().name("ann").age(1));
    }

    /**
     * 先保存一行再抛出异常；声明式事务必须回滚该行。
     *
     * @throws IllegalStateException 总是抛出
     */
    @Transactional
    public void rollbackWork() {
        repo.save(repo.createEntity().name("ann").age(2));
        throw new IllegalStateException("boom");
    }
}