package io.github.erdsgfc.jforge.logsql;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/** 用于 {@code logSql=true} 包的测试实体。 */
@Table(name = "log_users")
public interface LogUser {

    @Id
    @GeneratedValue
    Long id();

    LogUser id(Long id);

    @Column(name = "user_name")
    String name();

    LogUser name(String name);
}
