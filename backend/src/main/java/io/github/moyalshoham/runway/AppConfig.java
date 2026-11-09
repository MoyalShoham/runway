package io.github.moyalshoham.runway;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /** "Today" is Israel's today, whatever the container's time zone. */
    @Bean
    Clock clock() {
        return Clock.system(java.time.ZoneId.of("Asia/Jerusalem"));
    }

    /**
     * Money leaves the API as a decimal string ("1234.50"), never a JSON number, so no client parses
     * it into a binary float on the way in.
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer moneyAsString() {
        return builder -> builder.serializerByType(BigDecimal.class, new JsonSerializer<BigDecimal>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(value.toPlainString());
            }
        });
    }
}
