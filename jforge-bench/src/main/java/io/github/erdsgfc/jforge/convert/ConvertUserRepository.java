package io.github.erdsgfc.jforge.convert;

import io.github.erdsgfc.jforge.annotation.Bind;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.Query;
import io.github.erdsgfc.jforge.core.BaseRepository;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

/** 自定义类型转换实体（{@code @Convert}）的仓库。 */
@Dao
public interface ConvertUserRepository extends BaseRepository<ConvertUser, Long> {

    /**
     * 按转换列查询：{@code birth_date} 列存转换后文本，{@code @Bind} 挂同一转换器
     * （{@link StringDateConverter}，默认 {@code JDBCType.OTHER}）——参数经
     * {@code CONV.toDatabase(d)} 生成相同文本表示才能命中。静态路径（参数非
     * {@code @Nullable}）。
     */
    @Query("SELECT * FROM convert_users WHERE birth_date = :d")
    ConvertUser findByBirthDate(@Bind(value = "d", converter = StringDateConverter.class) LocalDate d);

    /**
     * 动态路径：{@code @Nullable} 参数 + {@code @Bind} 转换器（{@link VarcharDateConverter}，
     * 显式 {@code sqlType() = VARCHAR}）——运行时 {@code null} 跳过条件、非 null 经转换器
     * 绑定（运行时索引 {@code i++}）。
     */
    @Query("SELECT * FROM convert_users WHERE hire_date = :d")
    ConvertUser findNullableByHireDate(
            @Nullable @Bind(value = "d", converter = VarcharDateConverter.class) LocalDate d);
}