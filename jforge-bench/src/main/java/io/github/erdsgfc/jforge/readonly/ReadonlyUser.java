package io.github.erdsgfc.jforge.readonly;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/**
 * 只声明 getter 的"只读"实体：没有 builder setter。列映射照常工作，
 * 生成的 impl 会额外生成接口上不存在的 setter（供行映射内部填充）。
 */
@Table(name = "readonly_users")
public interface ReadonlyUser {

    /** 数据库生成的主键（BIGSERIAL）。 */
    @Id
    @GeneratedValue
    Long id();

    /** 用户显示名，映射到 {@code user_name} 列。 */
    @Column(name = "user_name")
    String name();

    /** 用户年龄（岁）。 */
    Integer age();
}
