package de.grouppurse.web;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Eindeutige, sprechende operationIds (z.B. ledgerBalance), damit der generierte TypeScript-Client brauchbare Namen bekommt. */
@Configuration
public class OpenApiConfig {
    @Bean
    OperationCustomizer operationIds() {
        return (operation, handlerMethod) -> {
            String controller = handlerMethod.getBeanType().getSimpleName().replace("Controller", "");
            String method = handlerMethod.getMethod().getName();
            operation.setOperationId(Character.toLowerCase(controller.charAt(0)) + controller.substring(1)
                    + Character.toUpperCase(method.charAt(0)) + method.substring(1));
            return operation;
        };
    }
}
