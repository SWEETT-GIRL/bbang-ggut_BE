package sweetgirl.croissant.config.docs;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

@Configuration
public class SwaggerConfig {

	@Bean
	public OpenAPI openApi() {
		String jwtScheme = "bearerAuth";
		return new OpenAPI()
			.info(new Info()
				.title("빵긋 API")
				.description("빵긋 서비스 API 문서")
				.version("v1.0.0"))
			.servers(List.of(
				new Server().url("http://localhost:8080").description("로컬")))
			.addSecurityItem(new SecurityRequirement().addList(jwtScheme))
			.components(new Components()
				.addSecuritySchemes(jwtScheme, new SecurityScheme()
					.name(jwtScheme)
					.type(SecurityScheme.Type.HTTP)
					.scheme("bearer")
					.bearerFormat("JWT")));
	}
}
