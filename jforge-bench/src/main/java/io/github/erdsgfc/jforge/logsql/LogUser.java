package io.github.erdsgfc.jforge.logsql;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/** Test entity for the {@code logSql=true} package. */
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
