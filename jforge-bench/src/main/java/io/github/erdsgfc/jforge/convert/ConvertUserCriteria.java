package io.github.erdsgfc.jforge.convert;

import io.github.erdsgfc.jforge.annotation.JForgeSql;

import java.time.LocalDate;

/**
 * {@code @Where} 条件对象：字段映射实体字段时自动复用命中列（{@code birth_date}）
 * 的 {@code @Convert} 转换器——条件值经 {@code CONV.toDatabase(...)} 生成与列存
 * 一致的表示才能命中。
 */
@JForgeSql
public class ConvertUserCriteria {

    /** 映射实体 birthDate 字段 → birth_date 列（@Convert：LocalDate ↔ "yyyy-MM-dd" 文本）。 */
    public LocalDate birthDate;

    public LocalDate getBirthDate() {
        return birthDate;
    }
}
