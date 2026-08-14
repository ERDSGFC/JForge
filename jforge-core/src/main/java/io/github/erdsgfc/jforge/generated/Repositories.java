package io.github.erdsgfc.jforge.generated;

import io.github.erdsgfc.jforge.TransactionManager;

import javax.sql.DataSource;

/**
 * 空壳占位类（不要在用户代码中直接使用）。
 *
 * <p>注解处理器会在用户项目同包（固定包 {@code io.github.erdsgfc.jforge.generated}）生成真实的
 * {@code Repositories}（含每个 {@code @Dao} 的类型分发创建）。运行时用户项目 target/classes 中的
 * 类文件按 Java 类加载优先级覆盖本 jar 里的同名占位类，因此本占位仅用于让框架代码（如
 * {@code JForge} 门面）能编译期引用该类。直接调用会失败——说明该模块未运行注解处理器或无
 * {@code @Dao} 仓库。</p>
 */
public final class Repositories {

    private Repositories() {
    }

    /**
     * 按仓库接口类型创建实现（占位实现直接抛错，真实实现由注解处理器生成）。
     *
     * @param type               the repository interface type
     * @param dataSource         the data source for the repository
     * @param transactionManager the transaction manager the repository will hold
     * @param <T>                the repository type
     * @return a new repository instance
     * @throws UnsupportedOperationException always (placeholder not overridden)
     */
    public static <T> T create(Class<T> type, DataSource dataSource, TransactionManager transactionManager) {
        throw new UnsupportedOperationException(
                "Repositories placeholder: the annotation processor did not generate a real "
                + "implementation for this module (no @Dao repository, or the processor did not run).");
    }
}
