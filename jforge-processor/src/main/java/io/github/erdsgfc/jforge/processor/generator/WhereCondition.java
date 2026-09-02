package io.github.erdsgfc.jforge.processor.generator;

import com.palantir.javapoet.MethodSpec;
import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.annotation.Op;
import io.github.erdsgfc.jforge.processor.EntityModel;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.utils.Nullability;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;

/** 一个统一的 WHERE 条件节点。 */
record WhereCondition(String columnName, String op, String paramName, String typeName, boolean dynamic,
                      boolean optional, String valueExpr, String rawSql, String converterField,
                      boolean collection, boolean array, String elementTypeName) {

    static WhereCondition resolveHost(JForgeProcessor.DaoInfo info, ExecutableElement method,
                                      VariableElement parameter, ProcessingEnvironment env,
                                      String diagnosticPrefix) {
        String paramName = parameter.getSimpleName().toString();
        Condition condition = parameter.getAnnotation(Condition.class);
        String fieldName = condition != null && !condition.value().isEmpty()
                ? condition.value() : paramName;
        String op = condition != null ? condition.op().sql() : "=";
        String rawSql = condition != null ? condition.rawSql() : "";
        boolean optional = CriteriaGenerator.isOptional(parameter.asType());
        TypeMirror type = env.getTypeUtils().stripAnnotations(parameter.asType());
        boolean array = type.getKind() == TypeKind.ARRAY;
        boolean collection = !optional && isIterable(type, env);
        if ((array || collection) && condition != null && condition.op() != Op.EQ) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Iterable/array WHERE parameters only support @Condition(op = EQ)", parameter);
            return null;
        }
        if (array && ((ArrayType) type).getComponentType().getKind() == TypeKind.ARRAY) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Multi-dimensional array WHERE parameters are not supported", parameter);
            return null;
        }
        boolean primitive = type.getKind().isPrimitive();
        boolean dynamic = !primitive && Nullability.isNullableParameter(parameter);
        String elementType = null;
        if (array) {
            elementType = ((ArrayType) type).getComponentType().toString();
        } else if (collection) {
            elementType = iterableElementType(type, env);
        }
        String bindType = optional ? CriteriaGenerator.optionalValueType(type, env.getTypeUtils())
                : elementType != null ? elementType : type.toString();
        String valueExpr = optional ? paramName + CriteriaGenerator.optionalValueMethod(type) : null;
        if (!rawSql.isEmpty()) {
            if (collection || array) {
                env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Iterable/array parameters cannot be used with @Condition(rawSql)", parameter);
                return null;
            }
            return new WhereCondition(null, op, paramName, bindType, dynamic, optional, valueExpr,
                    rawSql, null, false, false, null);
        }
        for (EntityModel.ColumnModel column : info.model.columns()) {
            if (column.fieldName.equals(fieldName)) {
                String converter = column.converter != null
                        ? SqlCodegen.converterFieldName(info.model, column) : null;
                return new WhereCondition(SqlCodegen.quoteIdentifier(info.model.dialectSupport(), column.columnName),
                        op, paramName, bindType, dynamic, optional, valueExpr, null, converter,
                        collection, array, elementType);
            }
        }
        env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                diagnosticPrefix + " parameter field '" + fieldName + "' does not match any field of entity "
                        + info.model.entityQualifiedName(), method);
        return null;
    }

    private static boolean isIterable(TypeMirror type, ProcessingEnvironment env) {
        if (type.getKind() != TypeKind.DECLARED) return false;
        TypeMirror iterable = env.getElementUtils().getTypeElement("java.lang.Iterable").asType();
        return env.getTypeUtils().isAssignable(env.getTypeUtils().erasure(type),
                env.getTypeUtils().erasure(iterable));
    }

    private static String iterableElementType(TypeMirror type, ProcessingEnvironment env) {
        if (type.getKind() != TypeKind.DECLARED) return "java.lang.Object";
        List<? extends TypeMirror> args = ((DeclaredType) type).getTypeArguments();
        return args.isEmpty() ? "java.lang.Object" : env.getTypeUtils().stripAnnotations(args.getFirst()).toString();
    }

    boolean staticCompatible() {
        return !dynamic && !collection && !array && !optional;
    }

    static void appendSql(MethodSpec.Builder spec, WhereCondition c) {
        if (c.dynamic) spec.beginControlFlow("if ($N != null)", c.paramName);
        if (c.collection || c.array) {
            String size = c.array ? c.paramName + ".length" : "sqlValues_" + c.paramName + ".size()";
            if (c.collection) {
                spec.addStatement("$T<$L> sqlValues_$L = new $T<>()", List.class,
                        c.elementTypeName, c.paramName, ArrayList.class);
                spec.beginControlFlow("for ($L value : $N)", c.elementTypeName, c.paramName);
                spec.addStatement("sqlValues_$L.add(value)", c.paramName);
                spec.endControlFlow();
            }
            spec.beginControlFlow("if ($L == 0)", size);
            spec.addStatement("sql.append(where).append($S)", " 1 = 0");
            spec.nextControlFlow("else");
            spec.addStatement("sql.append(where).append($S)", " " + c.columnName + " IN (");
            spec.beginControlFlow("for (int j = 0; j < $L; j++)", size);
            spec.addStatement("if (j > 0) sql.append($S)", ", ");
            spec.addStatement("sql.append($S)", "?");
            spec.endControlFlow();
            spec.addStatement("sql.append($S)", ")");
            spec.endControlFlow();
        } else if (c.rawSql != null) {
            spec.addStatement("sql.append(where).append($S)", " " + c.rawSql);
        } else if (c.optional) {
            spec.beginControlFlow("if ($N.isPresent())", c.paramName);
            spec.addStatement("sql.append(where).append($S)", " " + c.columnName + " " + c.op + " ?");
            spec.nextControlFlow("else");
            spec.addStatement("sql.append(where).append($S)", " " + c.columnName + " IS NULL");
            spec.endControlFlow();
        } else {
            spec.addStatement("sql.append(where).append($S)", " " + c.columnName + " " + c.op + " ?");
        }
        spec.addStatement("where = $S", " AND ");
        if (c.dynamic) spec.endControlFlow();
    }

    static void appendBind(MethodSpec.Builder spec, WhereCondition c) {
        if (c.dynamic) spec.beginControlFlow("if ($N != null)", c.paramName);
        if (c.collection || c.array) {
            String size = c.array ? c.paramName + ".length" : "bindValues_" + c.paramName + ".size()";
            if (c.collection) {
                spec.addStatement("$T<$L> bindValues_$L = new $T<>()", List.class,
                        c.elementTypeName, c.paramName, ArrayList.class);
                spec.beginControlFlow("for ($L value : $N)", c.elementTypeName, c.paramName);
                spec.addStatement("bindValues_$L.add(value)", c.paramName);
                spec.endControlFlow();
            }
            spec.beginControlFlow("for (int j = 0; j < $L; j++)", size);
            String expr = c.array ? c.paramName + "[j]" : "bindValues_" + c.paramName + ".get(j)";
            spec.addCode(SqlCodegen.bindParam(c.typeName, expr, "i++", true, false, c.converterField));
            spec.addCode("\n");
            spec.endControlFlow();
        } else if (c.rawSql != null && !c.rawSql.contains("?")) {
            // no binding
        } else if (c.optional) {
            spec.beginControlFlow("if ($N.isPresent())", c.paramName);
            spec.addCode(SqlCodegen.bindParam(c.typeName, c.valueExpr, "i++", false, false, c.converterField));
            spec.addCode("\n");
            spec.endControlFlow();
        } else {
            spec.addCode(SqlCodegen.bindParam(c.typeName, c.paramName, "i++", false, false, c.converterField));
            spec.addCode("\n");
        }
        if (c.dynamic) spec.endControlFlow();
    }

    static void appendStaticWhereSql(StringBuilder sql, List<WhereCondition> conditions) {
        if (conditions.isEmpty()) return;
        sql.append(" WHERE ");
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) sql.append(" AND ");
            WhereCondition c = conditions.get(i);
            sql.append(c.rawSql != null ? c.rawSql : c.columnName + " " + c.op + " ?");
        }
    }

    static int appendStaticBinds(MethodSpec.Builder spec, List<WhereCondition> conditions, int index) {
        for (WhereCondition c : conditions) {
            if (c.rawSql != null && !c.rawSql.contains("?")) continue;
            spec.addCode(SqlCodegen.bindParam(c.typeName, c.valueExpr != null ? c.valueExpr : c.paramName,
                    index++, false, false, c.converterField));
            spec.addCode("\n");
        }
        return index;
    }
}
