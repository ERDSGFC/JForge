package io.github.erdsgfc.jforge.pgsql;

/** {@code @Select} record 投影：组件名经命名策略映射列（{@code userName} → {@code user_name}）。 */
public record PgUserNameDto(Long id, String userName) {
}
