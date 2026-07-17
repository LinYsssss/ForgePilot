package com.example.codereview.language.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JavaChangeContextExtractor {

    private final JavaParser parser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));

    @SuppressWarnings("unchecked")
    public JavaSymbolContext extract(Map<String, String> changedSources) {
        Set<String> classes = new LinkedHashSet<>();
        Set<String> methods = new LinkedHashSet<>();
        Set<String> annotations = new LinkedHashSet<>();
        Set<String> calls = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        if (changedSources == null) {
            return new JavaSymbolContext(List.of(), List.of(), List.of(), List.of(), List.of());
        }
        changedSources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ParseResult<CompilationUnit> result = parser.parse(entry.getValue() == null ? "" : entry.getValue());
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                errors.add(entry.getKey() + ": " + result.getProblems().stream()
                        .findFirst().map(problem -> problem.getMessage()).orElse("parse failed"));
                return;
            }
            CompilationUnit unit = result.getResult().orElseThrow();
            String packageName = unit.getPackageDeclaration()
                    .map(declaration -> declaration.getNameAsString() + ".").orElse("");
            unit.findAll(ClassOrInterfaceDeclaration.class).forEach(type ->
                    classes.add(packageName + type.getNameAsString()));
            unit.findAll(MethodDeclaration.class).forEach(method -> {
                String owner = method.findAncestor(ClassOrInterfaceDeclaration.class)
                        .map(type -> packageName + type.getNameAsString()).orElse(packageName.replaceAll("\\.$", ""));
                String parameters = method.getParameters().stream()
                        .map(parameter -> parameter.getType().asString()).reduce((left, right) -> left + "," + right)
                        .orElse("");
                methods.add(owner + "#" + method.getNameAsString() + "(" + parameters + ")");
            });
            unit.findAll(AnnotationExpr.class).forEach(annotation -> annotations.add(annotation.getNameAsString()));
            unit.findAll(MethodCallExpr.class).forEach(call -> {
                String value = call.getScope().map(scope -> scope + ".").orElse("") + call.getNameAsString();
                calls.add(value);
            });
        });
        return new JavaSymbolContext(
                sorted(classes), sorted(methods), sorted(annotations), sorted(calls), List.copyOf(errors));
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }
}
