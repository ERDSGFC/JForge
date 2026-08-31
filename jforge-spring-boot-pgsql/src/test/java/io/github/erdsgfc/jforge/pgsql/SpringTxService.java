package io.github.erdsgfc.jforge.pgsql;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@code @Transactional} 服务：声明式事务控制仓库写入（真 PG 验证）。 */
@Service
class SpringTxService {

    private final PgUserRepository repo;

    SpringTxService(PgUserRepository repo) {
        this.repo = repo;
    }

    /** 事务内保存两条并计数（提交后数据可见）。 */
    @Transactional
    long saveTwoAndCount() {
        repo.save(repo.createEntity().userName("tx-a").age(1));
        repo.save(repo.createEntity().userName("tx-b").age(2));
        return repo.count();
    }

    /** 事务内保存后抛异常（应整体回滚）。 */
    @Transactional
    void saveThenThrow() {
        repo.save(repo.createEntity().userName("tx-rollback").age(1));
        throw new IllegalStateException("boom");
    }
}
