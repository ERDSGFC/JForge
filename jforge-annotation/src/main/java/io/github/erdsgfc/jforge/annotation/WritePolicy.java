package io.github.erdsgfc.jforge.annotation;

/**
 * 列的写入策略——控制列参与 {@code INSERT}（save）与 {@code UPDATE SET}（update）的组合。
 * 由 {@link Column#write()} 显式指定；缺省 {@link #BOTH}。
 *
 * <p>列能否进入写 SQL 还受"值来源"约束：既无 setter 又无 default getter 的纯只读列
 * （由数据库维护）即使策略为 {@link #BOTH} 也不会进入任何写语句。</p>
 */
public enum WritePolicy {

    /** INSERT 与 UPDATE SET 都包含该列（可写列的默认行为）。 */
    BOTH,

    /** 仅 INSERT 包含该列——save 时写入（如用 default 填一次），update 不触碰。 */
    INSERT_ONLY,

    /** 仅 UPDATE SET 包含该列——update 时刷新，INSERT 不含（列由数据库默认值或可空处理）。 */
    UPDATE_ONLY,

    /** INSERT 与 UPDATE SET 都不包含该列（纯只读，由数据库维护）。 */
    NONE
}
