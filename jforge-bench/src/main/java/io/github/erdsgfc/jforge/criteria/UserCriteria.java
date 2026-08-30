package io.github.erdsgfc.jforge.criteria;

import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.annotation.Or;
import io.github.erdsgfc.jforge.annotation.Op;

import java.util.Optional;

/**
 * {@code @Where} 条件对象：值字段 → 单条件（null 跳过）；{@code Optional} 字段 →
 * 空 = IS NULL、有值 = 条件；自定义类字段 → 括号分组；{@link Or} 定义与上一条件
 * 的连接方式。
 */
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

    /** AND (city = ? AND street = ?)（null 跳过整个括号）。 */
    public AddressCriteria address;

    public String getName() {
        return name;
    }

    public Integer getAge() {
        return age;
    }

    public Optional<String> getNickname() {
        return nickname;
    }

    public AddressCriteria getAddress() {
        return address;
    }
}
