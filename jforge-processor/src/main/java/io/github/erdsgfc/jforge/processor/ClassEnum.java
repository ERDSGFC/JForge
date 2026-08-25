package io.github.erdsgfc.jforge.processor;

import com.palantir.javapoet.ClassName;

public enum ClassEnum {

    BASE_REPOSITORY("io.github.erdsgfc.jforge.core", "BaseRepository"),
    BASE_REPOSITORY_FACTORY("io.github.erdsgfc.jforge.generated", "Repositories"),
    TRANSACTION_MANAGER("io.github.erdsgfc.jforge", "TransactionManager"),
    JDBC_DATASOURCE("javax.sql", "DataSource"),
    ;

    ClassEnum(String packagePath, String className) {
        this.packagePath = packagePath;
        this.className = className;
        this.fullClassName = packagePath + "." + className;
        this.javaPoetClassName = ClassName.get(packagePath, className);
    }

    private final String packagePath;
    private final String className;
    private final String fullClassName;
    private final ClassName javaPoetClassName;
    public String getPackagePath() {
        return packagePath;
    }
    public String getClassName() {
        return className;
    }
    public String getFullClassName() {
        return fullClassName;
    }
    public ClassName getJavaPoetClassName() {
        return javaPoetClassName;
    }
}
