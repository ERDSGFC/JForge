package io.github.erdsgfc.jforge.criteria;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/** 条件对象查询（@Where）的验证实体。 */
@Table(name = "criteria_users")
public interface CriteriaUser {

    /** 数据库生成的主键（BIGSERIAL）。 */
    @Id
    @GeneratedValue
    Long id();

    CriteriaUser id(Long id);

    /** 用户显示名，映射到 {@code user_name} 列。 */
    @Column(name = "user_name")
    String name();

    CriteriaUser name(String name);

    /** 用户年龄（岁）。 */
    Integer age();

    CriteriaUser age(Integer age);

    /** 城市（嵌套条件组字段映射的列）。 */
    String city();

    CriteriaUser city(String city);

    /** 街道（嵌套条件组字段映射的列）。 */
    String street();

    CriteriaUser street(String street);

    /** 分数（rawSql SET 表达式测试用）。 */
    Integer score();

    CriteriaUser score(Integer score);
}
