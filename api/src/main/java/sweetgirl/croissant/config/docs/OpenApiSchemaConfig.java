package sweetgirl.croissant.config.docs;

import java.time.LocalTime;

import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.media.StringSchema;

/**
 * API 명세(springdoc)를 실제 응답과 맞춘다. 앱은 이 명세로 응답 타입을 자동 생성하므로 명세가 틀리면 앱 타입이 틀어진다.
 *
 * <p>SwaggerConfig 는 local·staging 전용이라, 테스트에서 명세를 뽑을 때도 적용되도록 프로파일 없이 둔다.
 */
@Configuration
public class OpenApiSchemaConfig {

	static {
		// 실제 응답은 Jackson 이 LocalTime 을 "08:00:00" 문자열로 쓴다. springdoc 기본값은 {hour, minute, second, nano} 객체다
		SpringDocUtils.getConfig()
			.replaceWithSchema(LocalTime.class, new StringSchema().format("time").example("08:00:00"));
	}
}
