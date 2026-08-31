package io.github.erdsgfc.jforge.pgsql;

import io.github.erdsgfc.jforge.annotation.JForgeConverter;

/**
 * 任意数据库类型转换验证：{@code String}（JSON 文本）↔ PG {@code jsonb} 列。
 *
 * <p>读取方向裸 {@code rs.getObject(i)} 返回驱动的默认表示 {@code PGobject}
 * （旧双泛型方案的 {@code getObject(i, Y.class)} 无法表达这种数据库特有类型），
 * 经 {@code toEntity} 取 {@code toString()} 还原 JSON 文本——证明单泛型
 * {@code Object} 签名适配任意数据库类型。</p>
 */
public final class JsonTextConverter implements JForgeConverter<String> {

    @Override
    public Object toDatabase(String attribute) {
        return attribute; // JSON 文本按字符串绑定,PG 按目标列(jsonb)解析
    }

    @Override
    public String toEntity(Object dbData) {
        // PG 驱动对 jsonb 列的默认表示是 PGobject,toString() 即 JSON 文本。
        return dbData == null ? null : dbData.toString();
    }
}
