package io.github.erdsgfc.jforge.infer;

import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;

/**
 * 无 {@code @Table} 的实体:表名按类名推断为 snake_case({@code inferred_table_entity})。
 */
public interface InferredTableEntity {

    @Id
    @GeneratedValue
    Long id();

    InferredTableEntity id(Long id);

    String name();

    InferredTableEntity name(String name);
}
