package io.github.erdsgfc.jforge.criteria;

import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.annotation.JForgeSql;
import io.github.erdsgfc.jforge.annotation.UpdateSet;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * {@code @Update} 条件的对象载体：一个对象同时携带修改字段与条件字段——
 * 顶层标 {@code @UpdateSet} 的字段是 SET 修改列（值 = {@code criteria.getX()}），
 * 其余字段是 WHERE 条件。
 */
@JForgeSql
public class UserUpdateCriteria {

    /** SET user_name = ?（name 字段映射 CriteriaUser.name 列）。 */
    @UpdateSet
    public String name;

    /** SET age = ?（null → 跳过该 SET，保持原值）。 */
    @UpdateSet
    public @Nullable Integer age;

    /** SET score = NULL（Optional 空）或 score = ?（有值）。 */
    @UpdateSet
    public Optional<Integer> score;

    /** WHERE user_name = ?（条件字段，映射 name 列）。 */
    @Condition(value = "name")
    public String nickname;

    /** WHERE id = ?。 */
    public Long id;

    public String getName() {
        return name;
    }

    public @Nullable Integer getAge() {
        return age;
    }

    public Optional<Integer> getScore() {
        return score;
    }

    public String getNickname() {
        return nickname;
    }

    public Long getId() {
        return id;
    }
}
