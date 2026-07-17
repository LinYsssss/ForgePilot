package com.example.codereview.language.java;

import java.util.List;

public record JavaSymbolContext(
        List<String> classes,
        List<String> methods,
        List<String> annotations,
        List<String> calls,
        List<String> parseErrors) {

    public JavaSymbolContext {
        classes = copy(classes);
        methods = copy(methods);
        annotations = copy(annotations);
        calls = copy(calls);
        parseErrors = copy(parseErrors);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
