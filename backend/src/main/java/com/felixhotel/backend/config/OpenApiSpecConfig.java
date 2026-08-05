package com.felixhotel.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Espone lo spec OpenAPI del progetto come risorsa statica, cosi' che
 * Swagger UI possa caricarlo direttamente (vedi
 * {@code springdoc.swagger-ui.url} in application.properties).
 *
 * <p>Il file resta dov'e' sempre stato ({@code src/main/resources/openapi/},
 * da cui il generatore Maven produce DTO e interfacce a build-time): qui si
 * aggiunge solo la mappatura HTTP {@code /openapi/**} -> classpath, senza
 * doverlo spostare sotto {@code static/}.
 */
@Configuration
public class OpenApiSpecConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/openapi/**")
                .addResourceLocations("classpath:/openapi/");
    }
}
