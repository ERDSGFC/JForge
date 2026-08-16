package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 针对生成的 {@code save(List<T>)} 方法，按仓库或按方法覆盖 JDBC 批处理大小。
 *
 * <p>优先级高于 {@link JForgeConfig#batchSize()}：处理器按以下顺序解析：
 * 方法上的 {@code @BatchSize}，然后是仓库接口上的，
 * 然后是 {@code JForgeConfig} 值（包级或元素级），最后是默认值（{@code 50}）。</p>
 *
 * <p>标注在仓库接口上（{@code TYPE}）时，作用于该仓库所有可批处理的生成方法；
 * 标注在方法上（{@code METHOD}）时，仅作用于该方法——生成的批处理方法
 * （{@code save(List<T>)} 等）继承自 {@code BaseRepository}，因此要标注某个方法，
 * 仓库必须以完全相同的签名重声明它：</p>
 *
 * <pre>{@code
 * public interface UserRepository extends BaseRepository<UserEntity, Long> {
 *     // 该方法专用批处理大小(必须与 BaseRepository 签名一致)
 *     @BatchSize(500)
 *     List<UserEntity> save(List<UserEntity> entities);
 * }
 * }</pre>
 *
 * <p>值为 {@code 0} 时，对被标注的元素禁用批处理（行在单条连接上逐条插入）。</p>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface BatchSize {

    /**
     * JDBC 批处理块大小：每次 {@code executeBatch()} 之前调用 {@code addBatch()}
     * {@code value} 次。{@code 0} 表示禁用批处理。
     *
     * @return 批处理大小
     */
    int value();
}