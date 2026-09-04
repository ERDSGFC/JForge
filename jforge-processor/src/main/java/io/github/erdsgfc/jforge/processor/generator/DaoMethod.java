package io.github.erdsgfc.jforge.processor.generator;

import javax.lang.model.element.ExecutableElement;

/**
 * 一个待生成的 {@code @Dao} 方法：SQL 语义注解方法按注解分发给对应生成器时的
 * 载体——携带方法元素与同名序号，避免各生成器重复扫描接口方法、各自维护计数。
 *
 * @param method        仓库接口中声明的方法
 * @param overloadIndex 该方法在同名声明中的序号（0-based，含无注解与其他注解的
 *                      同名方法，由 {@code RepositoryGenerator} 单次遍历统一计数）——
 *                      SQL 常量字段名（{@code methodSqlFieldName}）依赖它保证唯一性
 */
public record DaoMethod(ExecutableElement method, int overloadIndex) {
}
