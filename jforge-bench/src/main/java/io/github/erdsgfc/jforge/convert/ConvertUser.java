package io.github.erdsgfc.jforge.convert;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.Convert;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

import java.time.LocalDate;

/**
 * 自定义类型转换实体：{@code birthDate} 列（VARCHAR）经 {@link StringDateConverter}
 * 在 Java {@code LocalDate} 与数据库字符串之间转换——验证 {@code @Convert} 在
 * save/批量 save/update/行映射（CRUD 与 {@code @Query}）全路径的生成代码。
 */
@Table(name = "convert_users")
public interface ConvertUser {

    /** 数据库生成的主键（BIGSERIAL）。 */
    @Id
    @GeneratedValue
    Long id();

    ConvertUser id(Long id);

    /** 姓名。 */
    String name();

    ConvertUser name(String name);

    /** 出生日期——VARCHAR 列存 {@code "yyyy-MM-dd"} 文本。 */
    @Column(name = "birth_date")
    @Convert(converter = StringDateConverter.class)
    LocalDate birthDate();

    ConvertUser birthDate(LocalDate birthDate);

    /** 显式列名的转换列（命名策略之外）。 */
    @Column(name = "hire_date")
    @Convert(converter = StringDateConverter.class)
    LocalDate hireDate();

    ConvertUser hireDate(LocalDate hireDate);
}
