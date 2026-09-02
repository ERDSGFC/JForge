package io.github.erdsgfc.jforge.criteria;

import io.github.erdsgfc.jforge.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * {@code @Where} 条件对象：值字段 → 单条件（null 跳过）；{@code Optional} 字段 →
 * 空 = IS NULL、有值 = 条件；自定义类字段 → 括号分组；{@link Or} 定义与上一条件
 * 的连接方式。
 */
@JForgeSql
public class UserCriteria {

    /** user_name = ?（null 跳过）。 */
    public String name;

    /** OR age > ?（null 跳过）。 */
    @Or
    @Condition(op = Op.GT)
    public Integer age;

    /** user_name IS NULL（Optional 空）或 user_name = ?（有值）；列映射实体 name 字段。 */
    @Condition(value = "name")
    public Optional<String> nickname;

    /** AND age IN (?,?,...)（空集合 → 1 = 0；映射实体 age 列）。 */
    @Condition(value = "age")
    public List<Integer> ages;

    /** AND (city = ? AND street = ?)（null 跳过整个括号）。 */
    @Where
    public AddressCriteria address;

    /** 原生 SQL 条件：非 null 时拼 age > 18（常量，无绑定）。 */
    @Condition(rawSql = "age > 18")
    public Integer adult;

    public String getName() {
        return name;
    }

    public Integer getAge() {
        return age;
    }

    public Optional<String> getNickname() {
        return nickname;
    }

    public List<Integer> getAges() {
        return ages;
    }

    public AddressCriteria getAddress() {
        return address;
    }

    public Integer getAdult() {
        return adult;
    }
}
