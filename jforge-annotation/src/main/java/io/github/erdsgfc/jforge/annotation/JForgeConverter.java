package io.github.erdsgfc.jforge.annotation;

/**
 * Java 类型 ↔ 数据库类型的转换 SPI：标了 {@link Convert} 的实体列，注解处理器在
 * 编译期把本接口的转换调用直接生成进 JDBC 代码（绑定 {@code ps.setObject(i,
 * converter.toDatabase(entity.getter()))}、读取 {@code setter(converter.toEntity(
 * rs.getObject(i)))}）——运行时零反射，转换逻辑就是生成代码的一部分。
 *
 * <p>约定：
 * <ul>
 *   <li>实现类必须有公开无参构造器（生成代码 {@code new XxxConverter()} 实例化）；</li>
 *   <li>{@link #toDatabase}/{@link #toEntity} 必须接受 {@code null} 输入（可空列的
 *       null 值直接透传，返回 {@code null} 即绑定/写入 NULL）；</li>
 *   <li>实现类需预编译或与实体同批编译（处理器经 {@code MirroredTypeException} 读取
 *       类型镜像，不要求类已在 classpath）。</li>
 * </ul>
 * </p>
 *
 * @param <X> 实体字段的 Java 类型
 * @param <Y> 数据库侧的 JDBC 类型（生成代码按 {@code Object} 绑定/读取，转换器内部强转）
 */
public interface JForgeConverter<X, Y> {

    /** 实体值 → 数据库值（{@code null} 透传）。 */
    Y toDatabase(X attribute);

    /** 数据库值 → 实体值（{@code null} 透传）。 */
    X toEntity(Y dbData);
}
