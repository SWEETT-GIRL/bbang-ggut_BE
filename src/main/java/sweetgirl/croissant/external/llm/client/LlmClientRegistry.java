package sweetgirl.croissant.external.llm.client;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.llm.dto.LlmResult;
import sweetgirl.croissant.external.llm.exception.LlmErrorCode;
import sweetgirl.croissant.external.llm.exception.LlmException;

/**
 * 벤더 어댑터를 모아 두고 호출을 한 문으로 좁힌다 (#662).
 *
 * <p>지연을 여기서만 잰다. 어댑터마다 재게 두면 무엇까지 포함했는지가 벤더마다 달라져(직렬화 포함 여부, 파싱
 * 포함 여부) 비교값이 어긋난다. 여기서 재면 셋 다 "요청 만들기부터 값이 손에 들어오기까지" 로 같다 — 회원이
 * 카드를 언제 보게 되는지에 가장 가까운 값이다.
 *
 * <p>이유 문장 서빙(#764)이 읽기 타임아웃만 다른 전용 레지스트리를 따로 두므로, 타입으로 주입받는 자리(실험 ·
 * 관리자 API)가 이쪽을 집게 기본 후보로 둔다.
 */
@Primary
@Component
public class LlmClientRegistry {

	private final Map<LlmProvider, LlmClient> clients = new EnumMap<>(LlmProvider.class);

	public LlmClientRegistry(List<LlmClient> llmClients) {
		llmClients.forEach(client -> clients.put(client.provider(), client));
	}

	/** 키가 설정된 벤더만 — 실험이 키 없는 벤더를 조합에서 빼는 데 쓴다 */
	public List<LlmProvider> configuredProviders() {
		return clients.values().stream()
			.filter(LlmClient::isConfigured)
			.map(LlmClient::provider)
			.toList();
	}

	public String defaultModelOf(LlmProvider provider) {
		return require(provider).defaultModel();
	}

	public <T> LlmResult<T> generateStructured(LlmRequest request, Class<T> responseType) {
		LlmClient client = require(request.provider());
		long startedAt = System.nanoTime();
		LlmResult<T> result = client.generateStructured(request, responseType);
		return result.withLatency(Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
	}

	private LlmClient require(LlmProvider provider) {
		LlmClient client = clients.get(provider);
		if (client == null || !client.isConfigured()) {
			throw new LlmException(LlmErrorCode.PROVIDER_NOT_CONFIGURED);
		}
		return client;
	}
}
