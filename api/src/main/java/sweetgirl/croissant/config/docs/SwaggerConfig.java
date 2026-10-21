package sweetgirl.croissant.config.docs;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

@Profile({"local", "staging"})
@Configuration
public class SwaggerConfig {

	@Value("${swagger.server-url:http://localhost:8080}")
	private String serverUrl;

	@Value("${swagger.server-description:로컬}")
	private String serverDescription;

	@Bean
	public OpenAPI openApi() {
		String jwtScheme = "bearerAuth";
		return new OpenAPI()
			.info(new Info()
				.title("빵긋 API")
				.description("빵긋 서비스 API 문서")
				.version("v1.0.0"))
			.servers(List.of(
				new Server().url(serverUrl).description(serverDescription)))
			.addSecurityItem(new SecurityRequirement().addList(jwtScheme))
			.components(new Components()
				.addSecuritySchemes(jwtScheme, new SecurityScheme()
					.name(jwtScheme)
					.type(SecurityScheme.Type.HTTP)
					.scheme("bearer")
					.bearerFormat("JWT")));
	}
}
