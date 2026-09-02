package io.github.erdsgfc.jforge.criteria;

import io.github.erdsgfc.jforge.annotation.JForgeSql;

/**
 * 嵌套条件组：{@link UserCriteria} 的自定义类字段——生成括号分组
 * {@code (city = ? AND street = ?)}，字段映射宿主实体列。
 */
@JForgeSql
public class AddressCriteria {

    /** 城市。 */
    public String city;

    /** 街道。 */
    public String street;

    public String getCity() {
        return city;
    }

    public String getStreet() {
        return street;
    }
}
