package io.github.erdsgfc.jforge.annotation;

import java.sql.JDBCType;
import java.sql.SQLType;

/**
 * Java 类型 ↔ 数据库类型的转换 SPI：标了 {@link Convert} 的实体列，注解处理器在
 * 编译期把本接口的转换调用直接生成进 JDBC 代码（绑定 {@code ps.setObject(i,
 * converter.toDatabase(entity.getter()), converter.sqlType())}、读取 {@code setter(
 * converter.toEntity(rs.getObject(i)))}）——运行时零反射，转换逻辑就是生成代码的一部分。
 *
 * <p>两个方向都以 {@code Object} 传递数据库侧值——<strong>适配任意数据库类型</strong>：
 * 写入经 {@code setObject(i, Object, SQLType)} 绑定，SQL 类型由 {@link #sqlType()}
 * 决定（默认 {@link JDBCType#OTHER} = unknown，由驱动/数据库按目标列推断，jsonb、
 * 数组等驱动不能直接映射的类型也能绑定）；读取经裸 {@code rs.getObject(i)} 拿驱动的
 * 默认表示（如 PG jsonb → {@code PGobject}、数组 → {@code PGobject[]}），由转换器
 * 内部强转/解析成实体字段类型。</p>
 *
 * <p>约定：
 * <ul>
 *   <li>实现类必须有公开无参构造器（生成代码 {@code new XxxConverter()} 实例化）；</li>
 *   <li>{@link #toDatabase}/{@link #toEntity} 必须接受 {@code null} 输入（可空列的
 *       null 值直接透传，返回 {@code null} 即绑定/写入 NULL）；</li>
 *   <li>{@link #sqlType()} 只在需要钉死绑定 SQL 类型时覆盖——{@code setObject(i, v,
 *       sqlType)} 会把值按该类型发送，对返回数据库原生对象（如 jsonb → {@code PGobject}）
 *       的转换器保持默认 {@code OTHER} 让服务端按列推断更稳妥；默认实现已返回
 *       {@link JDBCType#OTHER}，即旧行为，一般无需覆盖；</li>
 *   <li>{@link #toEntity} 的入参是驱动的默认数据库表示（非 null 时类型不定），
 *       转换器需按实际驱动类型强转（如 {@code (PGobject) dbData}）；</li>
 *   <li>实现类需预编译或与实体同批编译（处理器经 {@code MirroredTypeException} 读取
 *       类型镜像，不要求类已在 classpath）。</li>
 * </ul>
 * </p>
 *
 * @param <X> 实体字段的 Java 类型
 */
public interface JForgeConverter<X> {

    /** 实体值 → 数据库值（返回 {@code Object}，经 {@code setObject} 绑定；{@code null} 透传）。 */
    Object toDatabase(X attribute);

    /** 数据库值（驱动默认表示）→ 实体值（{@code null} 透传）。 */
    X toEntity(Object dbData);

    /**
     * 写入绑定的 SQL 类型（JDBC 4.2 {@link SQLType}）。默认 {@link JDBCType#OTHER}
     * （unknown，由驱动/数据库按目标列推断）——转换结果运行时类型不定（String、驱动
     * 原生对象如 {@code PGobject}…）时保持默认最稳妥；转换器确定输出应作为某具体类型
     * 发送时覆盖返回 {@code JDBCType.VARCHAR} 等，或返回驱动自定义 {@code SQLType}
     * 实现（如 PG 的 {@code PGType}）。生成代码在每个绑定点调用本方法，返回的
     * {@link SQLType} 实例直接传给 {@code setObject}。
     *
     * @return 绑定 SQL 类型
     */
    default SQLType sqlType() {
        return JDBCType.OTHER;
    }
}