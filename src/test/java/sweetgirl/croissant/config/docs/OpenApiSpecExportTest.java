package sweetgirl.croissant.config.docs;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.external.aws.S3Service;

/**
 * API 명세(openapi.json)를 build/openapi 에 뽑는다. BE CI 가 이 파일로 앱(FE) 타입을 다시 만들어 타입 검사를 돌린다.
 * 기본 test 에서는 빠진다 — ./gradlew test -PopenapiExport --tests "*OpenApiSpecExportTest"
 */
@Tag("openapi")
@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
class OpenApiSpecExportTest {

	private static final Path OUTPUT = Path.of("build", "openapi", "openapi.json");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private S3Service s3Service;

	@Test
	void exportsSpecThatMatchesRealResponses() throws Exception {
		String spec = mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		Files.createDirectories(OUTPUT.getParent());
		Files.writeString(OUTPUT, spec);

		JsonNode root = new ObjectMapper().readTree(spec);
		JsonNode schemas = root.path("components").path("schemas");
		// 영업시간은 실제 응답처럼 "08:00:00" 문자열이어야 한다
		assertThat(schemas.at("/BusinessHourResponse/properties/openTime/type").asText()).isEqualTo("string");
		// ApiResponse<T> 래퍼가 data 타입까지 풀려 있어야 앱 타입이 잡힌다
		assertThat(schemas.at("/ApiResponseListTopSubscribedStoreResponse/properties/data/items/$ref").asText())
			.endsWith("/TopSubscribedStoreResponse");
	}
}
