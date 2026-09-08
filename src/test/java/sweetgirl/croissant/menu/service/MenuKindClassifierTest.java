package sweetgirl.croissant.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.external.gemini.client.GeminiClient;
import sweetgirl.croissant.external.gemini.config.GeminiProperties;
import sweetgirl.croissant.external.gemini.dto.GeminiStructuredResult;
import sweetgirl.croissant.external.gemini.exception.GeminiErrorCode;
import sweetgirl.croissant.external.gemini.exception.GeminiException;
import sweetgirl.croissant.menu.config.MenuClassificationProperties;
import sweetgirl.croissant.menu.dto.classification.MenuKindVerdict;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.service.MenuKindClassifier.BatchAnswer;
import sweetgirl.croissant.menu.service.MenuKindClassifier.Item;

@ExtendWith(MockitoExtension.class)
class MenuKindClassifierTest {

	@Mock
	private GeminiClient geminiClient;

	private MenuKindClassifier classifier;

	@BeforeEach
	void setUp() {
		MenuClassificationProperties properties = new MenuClassificationProperties();
		properties.setRuns(3);
		properties.setMaxRetries(1);
		properties.setRetryBackoff(Duration.ZERO);
		classifier = new MenuKindClassifier(geminiClient, new GeminiProperties(), properties);
	}

	private static GeminiStructuredResult<BatchAnswer> answer(Item... items) {
		return new GeminiStructuredResult<>(new BatchAnswer(List.of(items)), 10, 5);
	}

	private static Item item(int index, String kind, boolean bakery, double confidence) {
		return new Item(index, kind, bakery, confidence);
	}

	private static Map<String, String> names(String... pairs) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put(pairs[i], pairs[i + 1]);
		}
		return map;
	}

	@Nested
	@DisplayName("3회 답을 하나의 판정으로")
	class Decide {

		@Test
		@DisplayName("세 번 다 같은 구체 kind → 확정")
		void agreed() {
			given(geminiClient.generateStructured(anyString(), anyMap(), eq(BatchAnswer.class)))
				.willReturn(answer(item(0, "카스테라", true, 0.9)))
				.willReturn(answer(item(0, "카스테라", true, 0.95)))
				.willReturn(answer(item(0, "카스테라", true, 1.0)));

			List<MenuKindVerdict> verdicts = classifier.classify(names("사각카스테라초코", "사각 카스테라(초코)"));

			assertThat(verdicts).hasSize(1);
			MenuKindVerdict verdict = verdicts.get(0);
			assertThat(verdict.kind()).isEqualTo(MenuKind.카스테라);
			assertThat(verdict.decided()).isTrue();
			assertThat(verdict.bakeryItem()).isTrue();
			assertThat(verdict.confidence()).isCloseTo(0.95, org.assertj.core.data.Offset.offset(0.001));
			verify(geminiClient, times(3)).generateStructured(anyString(), anyMap(), eq(BatchAnswer.class));
		}

		@Test
		@DisplayName("한 번이라도 다른 kind 면 미결 — confidence 가 높아도")
		void disagreed() {
			given(geminiClient.generateStructured(anyString(), anyMap(), eq(BatchAnswer.class)))
				.willReturn(answer(item(0, "크루아상", true, 0.99)))
				.willReturn(answer(item(0, "크루아상", true, 0.99)))
				.willReturn(answer(item(0, "페이스트리", true, 0.99)));

			MenuKindVerdict verdict = classifier.classify(names("아몬드뺑오쇼콜라", "아몬드 뺑오쇼콜라")).get(0);

			assertThat(verdict.decided()).isFalse();
			assertThat(verdict.kind()).isNull();
		}

		@Test
		@DisplayName("과반이 비-빵이면 '기타' 확정 — kind 가 갈려도")
		void nonBakeryByMajority() {
			given(geminiClient.generateStructured(anyString(), anyMap(), eq(BatchAnswer.class)))
				.willReturn(answer(item(0, "기타", false, 0.9)))
				.willReturn(answer(item(0, "빙수", true, 0.6)))
				.willReturn(answer(item(0, "기타", false, 0.9)));

			MenuKindVerdict verdict = classifier.classify(names("돼지바영치노", "1.1리터 보틀 돼지바영치노")).get(0);

			assertThat(verdict.kind()).isEqualTo(MenuKind.기타);
			assertThat(verdict.bakeryItem()).isFalse();
			assertThat(verdict.decided()).isTrue();
		}

		@Test
		@DisplayName("'빵'으로 세 번 합의해도 미결 — 모르겠다를 세 번 말한 것")
		void fallbackKindIsUndecided() {
			given(geminiClient.generateStructured(anyString(), anyMap(), eq(BatchAnswer.class)))
				.willReturn(answer(item(0, "빵", true, 0.9)))
				.willReturn(answer(item(0, "빵", true, 0.9)))
				.willReturn(answer(item(0, "빵", true, 0.9)));

			MenuKindVerdict verdict = classifier.classify(names("십원빵모짜렐라", "십원빵 모짜렐라")).get(0);

			assertThat(verdict.decided()).isFalse();
		}

		@Test
		@DisplayName("한 회차 응답에 index 가 빠지면 그 이름은 미결")
		void missingIndexIsUndecided() {
			given(geminiClient.generateStructured(anyString(), anyMap(), eq(BatchAnswer.class)))
				.willReturn(answer(item(0, "소금빵", true, 0.9), item(1, "쿠키", true, 0.9)))
				.willReturn(answer(item(0, "소금빵", true, 0.9)))
				.willReturn(answer(item(0, "소금빵", true, 0.9), item(1, "쿠키", true, 0.9)));

			List<MenuKindVerdict> verdicts = classifier.classify(names("소금빵", "소금빵", "쿠키", "쿠키"));

			assertThat(verdicts.get(0).kind()).isEqualTo(MenuKind.소금빵);
			assertThat(verdicts.get(1).decided()).isFalse();
		}

		@Test
		@DisplayName("빈 입력이면 Gemini 를 부르지 않는다")
		void emptyInput() {
			assertThat(classifier.classify(Map.of())).isEmpty();
			verify(geminiClient, never()).generateStructured(anyString(), anyMap(), any());
		}
	}

	@Nested
	@DisplayName("프롬프트")
	class Prompt {

		@Test
		@DisplayName("이름을 index 와 함께, kind 목록 전체를 실어 보낸다")
		void includesNamesAndKinds() {
			given(geminiClient.generateStructured(anyString(), anyMap(), eq(BatchAnswer.class)))
				.willReturn(answer(item(0, "소금빵", true, 0.9), item(1, "베이글", true, 0.9)));
			ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

			classifier.classify(names("소금빵", "쪽파 크림치즈 소금빵", "베이글", "플레인 쌀 베이글"));

			verify(geminiClient, times(3)).generateStructured(prompt.capture(), anyMap(), eq(BatchAnswer.class));
			assertThat(prompt.getValue())
				.contains("0. 쪽파 크림치즈 소금빵")
				.contains("1. 플레인 쌀 베이글")
				.contains("호두과자")
				.contains("단과자빵".substring(0, 0) + "단팥빵");
		}
	}

	@Nested
	@DisplayName("재시도")
	class Retry {

		@Test
		@DisplayName("429 는 쉬었다 다시 보내고 성공하면 그 답을 쓴다")
		void retriesOnRateLimit() {
			given(geminiClient.generateStructured(anyString(), anyMap(), eq(BatchAnswer.class)))
				.willThrow(new GeminiException(GeminiErrorCode.RATE_LIMITED))
				.willReturn(answer(item(0, "소금빵", true, 0.9)))
				.willReturn(answer(item(0, "소금빵", true, 0.9)))
				.willReturn(answer(item(0, "소금빵", true, 0.9)));

			MenuKindVerdict verdict = classifier.classify(names("소금빵", "소금빵")).get(0);

			assertThat(verdict.kind()).isEqualTo(MenuKind.소금빵);
			verify(geminiClient, times(4)).generateStructured(anyString(), anyMap(), eq(BatchAnswer.class));
		}

		@Test
		@DisplayName("재시도 한도를 넘기면 예외를 그대로 올린다 — 배치는 다음 주기에")
		void givesUpAfterMaxRetries() {
			given(geminiClient.generateStructured(anyString(), anyMap(), eq(BatchAnswer.class)))
				.willThrow(new GeminiException(GeminiErrorCode.RATE_LIMITED));

			assertThatThrownBy(() -> classifier.classify(names("소금빵", "소금빵")))
				.isInstanceOf(GeminiException.class);
			verify(geminiClient, times(2)).generateStructured(anyString(), anyMap(), eq(BatchAnswer.class));
		}

		@Test
		@DisplayName("재시도 불가 오류(400)는 바로 올린다")
		void doesNotRetryNonRetryable() {
			given(geminiClient.generateStructured(anyString(), anyMap(), eq(BatchAnswer.class)))
				.willThrow(new GeminiException(GeminiErrorCode.GENERATE_FAILED));

			assertThatThrownBy(() -> classifier.classify(names("소금빵", "소금빵")))
				.isInstanceOf(GeminiException.class);
			verify(geminiClient, times(1)).generateStructured(anyString(), anyMap(), eq(BatchAnswer.class));
		}
	}

	@Nested
	@DisplayName("즉답 제안 — 회원 메뉴 등록 화면")
	class Suggest {

		@Test
		@DisplayName("한 번만 묻고 그 kind 를 제안한다")
		void suggestsFromSingleCall() {
			given(geminiClient.generateStructured(anyString(), anyMap(), eq(BatchAnswer.class)))
				.willReturn(answer(item(0, "소금빵", true, 0.9)));

			assertThat(classifier.suggest("무화과크림치즈")).contains(MenuKind.소금빵);
			verify(geminiClient, times(1)).generateStructured(anyString(), anyMap(), eq(BatchAnswer.class));
		}

		@Test
		@DisplayName("비-빵이거나 '빵'·'기타'로만 답하면 제안하지 않는다")
		void noSuggestionForNonBakeryOrFallback() {
			given(geminiClient.generateStructured(anyString(), anyMap(), eq(BatchAnswer.class)))
				.willReturn(answer(item(0, "기타", false, 0.9)))
				.willReturn(answer(item(0, "빵", true, 0.9)));

			assertThat(classifier.suggest("허니레몬차")).isEmpty();
			assertThat(classifier.suggest("십원빵")).isEmpty();
		}

		@Test
		@DisplayName("Gemini 오류는 삼키고 빈 값 — 등록 자체를 막지 않는다")
		void swallowsGeminiFailure() {
			given(geminiClient.generateStructured(anyString(), anyMap(), eq(BatchAnswer.class)))
				.willThrow(new GeminiException(GeminiErrorCode.RATE_LIMITED));

			assertThat(classifier.suggest("소금빵")).isEmpty();
			verify(geminiClient, times(1)).generateStructured(anyString(), anyMap(), eq(BatchAnswer.class));
		}

		@Test
		@DisplayName("상한 안에 답이 없으면 빈 값 — 사용자를 기다리게 하지 않는다")
		void givesUpAfterTimeout() {
			MenuClassificationProperties slow = new MenuClassificationProperties();
			slow.setSuggestTimeout(Duration.ofMillis(50));
			MenuKindClassifier impatient = new MenuKindClassifier(geminiClient, new GeminiProperties(), slow);
			given(geminiClient.generateStructured(anyString(), anyMap(), eq(BatchAnswer.class)))
				.willAnswer(invocation -> {
					Thread.sleep(300);
					return answer(item(0, "소금빵", true, 0.9));
				});

			assertThat(impatient.suggest("소금빵")).isEmpty();
		}
	}
}
