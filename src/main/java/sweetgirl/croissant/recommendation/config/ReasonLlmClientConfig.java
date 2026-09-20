package sweetgirl.croissant.recommendation.config;

import java.net.http.HttpClient;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.external.gemini.client.GeminiClient;
import sweetgirl.croissant.external.gemini.client.GeminiLlmAdapter;
import sweetgirl.croissant.external.gemini.config.GeminiProperties;
import sweetgirl.croissant.external.llm.client.LlmClientRegistry;

/**
 * 이유 문장 서빙 전용 LLM 클라이언트 (#764).
 *
 * <p>공용 Gemini 클라이언트와 다른 것은 <b>읽기 타임아웃 하나</b>다. 공용은 메뉴 100건 배치 분류(실측 4~20초)를
 * 기다려야 해서 60초인데, 이 경로는 사용자가 4초만 기다린다. 기다림을 끊어도 HTTP 읽기는 멈추지 않으므로
 * 공용 값을 쓰면 벤더가 멈춘 1분 동안 전용 줄의 스레드가 묶이고, 그동안 들어온 요청은 전부 룰 문장을 받는다
 * (#765 리뷰).
 *
 * <p>지금은 Gemini 만 만든다. 설정이 다른 벤더를 가리키면 {@code ReasonSentenceCommandService} 가 기동 때 막는다 —
 * 전용 클라이언트가 없는 벤더로 조용히 공용 클라이언트를 타는 것보다 배포에서 드러나는 편이 낫다.
 */
@Configuration
public class ReasonLlmClientConfig {

	public static final String REASON_LLM_REGISTRY = "reasonLlmClientRegistry";

	@Bean(name = REASON_LLM_REGISTRY)
	public LlmClientRegistry reasonLlmClientRegistry(GeminiProperties geminiProperties,
			ReasonSentenceProperties reasonSentenceProperties, ObjectMapper objectMapper) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
			HttpClient.newBuilder().connectTimeout(geminiProperties.getConnectTimeout()).build());
		requestFactory.setReadTimeout(reasonSentenceProperties.getReadTimeout());
		RestClient restClient = RestClient.builder()
			.baseUrl(geminiProperties.getBaseUrl())
			.defaultHeader("x-goog-api-key", String.valueOf(geminiProperties.getKey()))
			.requestFactory(requestFactory)
			.build();
		return new LlmClientRegistry(List.of(
			new GeminiLlmAdapter(new GeminiClient(restClient, geminiProperties, objectMapper), geminiProperties,
				objectMapper)));
	}
}
