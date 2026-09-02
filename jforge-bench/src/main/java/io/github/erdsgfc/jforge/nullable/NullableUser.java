package io.github.erdsgfc.jforge.nullable;

import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 列空性判定的验证实体（包级 {@code @NullMarked}）：
 * <ul>
 *   <li>{@code name}——显式 {@code @Nullable} → 可空；</li>
 *   <li>{@code nickname}——显式 {@code @NonNull} → 非空；</li>
 *   <li>{@code age}——基本类型 {@code int} → 恒非空（NULL 读 0，无 wasNull 分支）；</li>
 *   <li>{@code score}——包装类也必须显式 {@code @Nullable} 才可空。</li>
 * </ul>
 */
@Table(name = "nullable_users")
public interface NullableUser {

    /** 数据库生成的主键（BIGSERIAL）。 */
    @Id
    @GeneratedValue
    @Nullable
    Long id();

    NullableUser id(@Nullable Long id);

    /** 可空：getter 返回类型标注 @Nullable。 */
    @Nullable
    String name();

    NullableUser name(@Nullable String name);

    /** 非空：显式 @NonNull。 */
    @NonNull
    String nickname();

    NullableUser nickname(@NonNull String nickname);

    /** 恒非空：基本类型。 */
    int age();

    NullableUser age(int age);

    /** 可空：包装类不再隐式视为可空。 */
    @Nullable
    Integer score();

    NullableUser score(@Nullable Integer score);
}
