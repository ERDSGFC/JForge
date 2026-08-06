package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Per-repository or per-method override of the JDBC batch size for the generated
 * {@code save(List<T>)} method.
 *
 * <p>Takes precedence over {@link JForgeConfig#batchSize()}: the processor
 * resolves, in order: a {@code @BatchSize} on the method, then on the repository
 * interface, then the {@code JForgeConfig} value (package or element), then the
 * default ({@code 50}).</p>
 *
 * <p>On a repository interface ({@code TYPE}) it applies to every batchable
 * generated method of that repository. On a method ({@code METHOD}) it applies to
 * that method only — the generated batch methods ({@code save(List<T>)} etc.) are
 * inherited from {@code BaseRepository}, so to annotate one the repository must
 * redeclare it with an identical signature:</p>
 *
 * <pre>{@code
 * public interface UserRepository extends BaseRepository<UserEntity, Long> {
 *     // 该方法专用批处理大小(必须与 BaseRepository 签名一致)
 *     @BatchSize(500)
 *     List<UserEntity> save(List<UserEntity> entities);
 * }
 * }</pre>
 *
 * <p>A value of {@code 0} disables batching for the annotated element (rows are
 * inserted one by one on a single connection).</p>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface BatchSize {

    /**
     * The JDBC batch chunk size: {@code addBatch()} is called {@code value} times
     * before each {@code executeBatch()}. {@code 0} disables batching.
     *
     * @return the batch size
     */
    int value();
}