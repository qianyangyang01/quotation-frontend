package com.milano.quotation.common;

import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class Jackson3HibernateConfig {
    @Bean
    HibernatePropertiesCustomizer jackson3JsonFormatMapper(ObjectMapper objectMapper) {
        var formatMapper = new Jackson3FormatMapper(objectMapper);
        return properties -> properties.put("hibernate.type.json_format_mapper", formatMapper);
    }

    static final class Jackson3FormatMapper implements FormatMapper {
        private final ObjectMapper mapper;

        Jackson3FormatMapper(ObjectMapper mapper) { this.mapper = mapper; }

        @Override
        public <T> T fromString(CharSequence source, JavaType<T> javaType, WrapperOptions options) {
            if (source == null) return null;
            try {
                return mapper.readValue(source.toString(), mapper.constructType(javaType.getJavaType()));
            } catch (JacksonException exception) {
                throw new IllegalArgumentException("Unable to read JSON value as " + javaType.getTypeName(), exception);
            }
        }

        @Override
        public <T> String toString(T value, JavaType<T> javaType, WrapperOptions options) {
            if (value == null) return null;
            try {
                return mapper.writeValueAsString(value);
            } catch (JacksonException exception) {
                throw new IllegalArgumentException("Unable to write JSON value of " + javaType.getTypeName(), exception);
            }
        }
    }
}
