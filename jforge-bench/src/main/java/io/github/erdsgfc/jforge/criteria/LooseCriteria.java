package io.github.erdsgfc.jforge.criteria;

/** 未标注 {@code @JForgeSql} 时由 {@code @Where(value = false)} 放宽校验的条件对象。 */
public class LooseCriteria {
    private String name;

    public LooseCriteria(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
