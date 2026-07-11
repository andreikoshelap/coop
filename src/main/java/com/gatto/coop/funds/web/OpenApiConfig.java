package com.gatto.coop.funds.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI fundsOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Funds Reservation API")
                .version("v1"));
    }
}
