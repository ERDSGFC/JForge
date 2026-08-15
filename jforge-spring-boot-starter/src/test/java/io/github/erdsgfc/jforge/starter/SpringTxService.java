package io.github.erdsgfc.jforge.starter;

import org.springframework.transaction.annotation.Transactional;

/**
 * A plain service bean whose {@code @Transactional} methods exercise repository
 * writes under Spring's declarative transaction control. It is registered as a
 * {@code @Bean} in the test configuration and proxied by Spring's transaction
 * interceptor. The repository inside joins the declarative transaction because the
 * generated code obtains its connection through its injected
 * {@link TransactionManager} → {@link SpringTransactionManager} →
 * {@code DataSourceUtils.getConnection}.
 */
public class SpringTxService {

    private final TestUserRepository repo;

    /**
     * @param repo the repository to write through
     */
    public SpringTxService(TestUserRepository repo) {
        this.repo = repo;
    }

    /** Saves a row inside a {@code @Transactional} boundary; commits on return. */
    @Transactional
    public void commitWork() {
        repo.save(repo.createEntity().name("ann").age(1));
    }

    /**
     * Saves a row then throws; the declarative transaction must roll the row back.
     *
     * @throws IllegalStateException always
     */
    @Transactional
    public void rollbackWork() {
        repo.save(repo.createEntity().name("ann").age(2));
        throw new IllegalStateException("boom");
    }
}