package com.example.chat.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Map <-> JSON 문자열 변환기 (MySQL JSON 컬럼용).
 * Hibernate 내장 JSON 매핑 대신 명시적으로 변환해서 Jackson 버전 차이에 영향받지 않게 한다.
 */
@Converter
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        return attribute == null ? null : MAPPER.writeValueAsString(attribute);
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        return dbData == null ? null : MAPPER.readValue(dbData, TYPE);
    }
}
