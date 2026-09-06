package io.github.erdsgfc.jforge.criteria;

import io.github.erdsgfc.jforge.annotation.JForgeSql;
import org.jspecify.annotations.NonNull;

/**
 * 全静态嵌套条件组：{@link UserStaticCriteria} 的自定义类字段——生成括号分组
 * {@code (city = ? AND street = ?)}，字段映射宿主实体列。字段全部显式
 * {@code @NonNull}（非空契约 → 静态折叠，无运行时跳过分支）。
 */
@JForgeSql
public class StaticAddressCriteria {

    /** 城市（非空契约）。 */
    public @NonNull String city;

    /** 街道（非空契约）。 */
    public @NonNull String street;

    public @NonNull String getCity() {
        return city;
    }

    public @NonNull String getStreet() {
        return street;
    }
}
