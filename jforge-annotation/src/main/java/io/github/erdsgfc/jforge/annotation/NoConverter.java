package io.github.erdsgfc.jforge.annotation;

/**
 * {@link Bind#converter()} 的哨兵默认值：表示"不挂转换器"。
 *
 * <p>仅作注解默认值的类型占位，注解处理器按全限定名识别并跳过，本类<b>永不被
 * 实例化</b>（转换器实现需公开无参构造器的约定对它不适用）。包私有——用户无需
 * 也无法直接引用它。</p>
 */
final class NoConverter implements JForgeConverter<Object> {

    private NoConverter() {
    }

    @Override
    public Object toDatabase(Object attribute) {
        return attribute;
    }

    @Override
    public Object toEntity(Object dbData) {
        return dbData;
    }
}