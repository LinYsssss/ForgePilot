package com.example.codereview.agent.model;

import com.example.codereview.agent.tool.AgentToolRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class LangChainToolSchemaMapper {

    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public LangChainToolSchemaMapper(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ToolSpecification map(AgentToolRegistry.ToolDescriptor descriptor) {
        return ToolSpecification.builder()
                .name(descriptor.name())
                .description(descriptor.description())
                .parameters(objectSchema(descriptor.inputType()))
                .build();
    }

    public List<ToolSpecification> mapAll(List<AgentToolRegistry.ToolDescriptor> descriptors) {
        return descriptors.stream().map(this::map).toList();
    }

    private JsonObjectSchema objectSchema(Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException("Agent tool input must be a record: " + type.getName());
        }
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder().additionalProperties(false);
        List<String> required = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            rejectExecutableField(component.getName());
            builder.addProperty(component.getName(), schema(component.getType()));
            required.add(component.getName());
        }
        builder.required(required);
        return builder.build();
    }

    private JsonSchemaElement schema(Class<?> type) {
        if (type == String.class || type.isEnum()) {
            return new JsonStringSchema();
        }
        if (type == boolean.class || type == Boolean.class) {
            return new JsonBooleanSchema();
        }
        if (type == byte.class || type == short.class || type == int.class || type == long.class
                || type == Byte.class || type == Short.class || type == Integer.class || type == Long.class) {
            return new JsonIntegerSchema();
        }
        if (Number.class.isAssignableFrom(type) || type == float.class || type == double.class) {
            return new JsonNumberSchema();
        }
        if (type.isRecord()) {
            return objectSchema(type);
        }
        throw new IllegalArgumentException("Unsupported Agent tool input field type: " + type.getName());
    }

    private void rejectExecutableField(String fieldName) {
        String normalized = fieldName.toLowerCase(Locale.ROOT);
        if (normalized.equals("command") || normalized.equals("shell")
                || normalized.contains("executablepath")) {
            throw new IllegalArgumentException("Executable fields are forbidden in model tool schemas");
        }
    }
}
