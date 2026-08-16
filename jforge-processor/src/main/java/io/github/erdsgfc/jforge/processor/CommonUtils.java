package io.github.erdsgfc.jforge.processor;

/**
 * 命名相关的纯字符串工具:从全限定名提取包名、camelCase 转 snake_case。
 *
 * <p>与类型转换工具({@link TypeNameUtils})、SQL 字符串工具({@link SqlCodegen})职责分离——
 * 本类只处理"名字"的纯字符串运算,不依赖 javac 的模型类型。</p>
 */
final class CommonUtils {

    private CommonUtils() {
    }

    /**
     * 从全限定名提取包名。
     *
     * @param qualifiedName 全限定名,如 {@code "com.example.UserEntity"}
     * @return 包名,如 {@code "com.example"};默认(未命名)包返回空字符串
     */
    static String packageOf(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? "" : qualifiedName.substring(0, dot);
    }

    /**
     * 把 camelCase 标识符转换为 snake_case,如 {@code userName} →
     * {@code user_name}:每个大写字母前加下划线(开头除外)并转小写。供列名推断
     * ({@code JForgeConfigHelper#columnName})与表名推断({@code EntityModel})共用。
     *
     * @param name camelCase 标识符,如 {@code "userName"}
     * @return snake_case 形式,如 {@code "user_name"}
     */
    static String camelToSnake(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0, len = name.length(); i < len; i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
