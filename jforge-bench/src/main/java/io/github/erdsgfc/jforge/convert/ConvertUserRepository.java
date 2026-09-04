package io.github.erdsgfc.jforge.convert;

import io.github.erdsgfc.jforge.annotation.Bind;
import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.Delete;
import io.github.erdsgfc.jforge.annotation.Query;
import io.github.erdsgfc.jforge.annotation.Select;
import io.github.erdsgfc.jforge.annotation.Update;
import io.github.erdsgfc.jforge.annotation.UpdateSet;
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
    ConvertUser findByBirthDate(@Bind(converter = StringDateConverter.class) LocalDate d);

    /**
     * 动态路径：{@code @Nullable} 参数 + {@code @Bind} 转换器（{@link VarcharDateConverter}，
     * 显式 {@code sqlType() = VARCHAR}）——运行时 {@code null} 跳过条件、非 null 经转换器
     * 绑定（运行时索引 {@code i++}）。
     */
    @Query("SELECT * FROM convert_users WHERE hire_date = :d")
    ConvertUser findNullableByHireDate(
            @Nullable @Bind(converter = VarcharDateConverter.class) LocalDate d);

    /**
     * {@code @Select}：条件参数名即宿主实体字段名，处理器自动复用该列 {@code @Convert}
     * 转换器（无需注解）——条件值经 {@code CONV.toDatabase(birthDate)} 生成与列一致的
     * 存储表示。
     */
    @Select
    ConvertUser findByBirthDateColumn(LocalDate birthDate);

    /**
     * {@code @Query} + {@code @Condition} 追加条件：{@code birthDate} 条件映射宿主列
     * {@code birth_date}，自动复用其转换器。
     */
    @Query("SELECT * FROM convert_users WHERE name = :name AND {:birthDate}")
    ConvertUser findByNameAndBirthDate(@Bind String name, @Condition LocalDate birthDate);

    /** 条件对象字段命中 @Convert 列：自动复用列转换器生成匹配表示。 */
    @Select
    ConvertUser findConvertByCriteria(@io.github.erdsgfc.jforge.annotation.Where ConvertUserCriteria criteria);

    /** {@code @Update}：SET 转换列（值经转换器写库）+ WHERE 主键。 */
    @Update
    int updateBirthDateById(@UpdateSet LocalDate birthDate, @Condition Long id);

    /** {@code @Delete}：WHERE 条件自动复用列转换器。 */
    @Delete
    int deleteByBirthDate(@Condition LocalDate birthDate);
}
