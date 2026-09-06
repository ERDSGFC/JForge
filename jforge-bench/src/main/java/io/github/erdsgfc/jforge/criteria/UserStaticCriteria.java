package io.github.erdsgfc.jforge.criteria;

import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.annotation.JForgeSql;
import io.github.erdsgfc.jforge.annotation.Op;
import io.github.erdsgfc.jforge.annotation.Where;
import org.jspecify.annotations.NonNull;

/**
 * 全静态条件对象：所有字段显式 {@code @NonNull}（非空契约）且非 Optional/集合——
 * WHERE 条件数量与占位符数量编译期确定，条件对象组折叠进 SQL 常量（静态形态），
 * 不做运行时 StringBuilder 拼接。
 */
@JForgeSql
public class UserStaticCriteria {

    /** user_name = ?（非空契约，静态折叠）。 */
    public @NonNull String name;

    /** AND age > ?（非空契约，静态折叠）。 */
    @Condition(op = Op.GT)
    public @NonNull Integer age;

    /** AND (city = ? AND street = ?)——嵌套组同样全静态。 */
    @Where
    public @NonNull StaticAddressCriteria address;

    public @NonNull String getName() {
        return name;
    }

    public @NonNull Integer getAge() {
        return age;
    }

    public @NonNull StaticAddressCriteria getAddress() {
        return address;
    }
}
