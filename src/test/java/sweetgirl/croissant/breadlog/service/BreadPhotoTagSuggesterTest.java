package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.dto.analysis.BreadPhotoToAnalyze;
import sweetgirl.croissant.breadlog.dto.analysis.RawTagSuggestion;
import sweetgirl.croissant.breadlog.dto.analysis.StoreMenuOption;
import sweetgirl.croissant.breadlog.service.BreadPhotoTagSuggester.Item;
import sweetgirl.croissant.breadlog.service.BreadPhotoTagSuggester.PhotoAnswer;
import sweetgirl.croissant.external.gemini.client.GeminiClient;
import sweetgirl.croissant.external.gemini.dto.GeminiStructuredResult;
import sweetgirl.croissant.external.gemini.exception.GeminiErrorCode;
import sweetgirl.croissant.external.gemini.exception.GeminiException;

@ExtendWith(MockitoExtension.class)
@DisplayName("사진 한 장 → 메뉴 제안")
class BreadPhotoTagSuggesterTest {

	private static final List<StoreMenuOption> MENUS = List.of(
		new StoreMenuOption(101L, "참깨 소금빵"),
		new StoreMenuOption(202L, "명란바게트"));
	private static final BreadPhotoToAnalyze PHOTO = new BreadPhotoToAnalyze("l0", "image/jpeg", new byte[] {1, 2, 3});

	@Mock
	private GeminiClient geminiClient;

	@InjectMocks
	private BreadPhotoTagSuggester breadPhotoTagSuggester;

	@Captor
	private ArgumentCaptor<String> promptCaptor;

	private void givenAnswer(Item... items) {
		given(geminiClient.generateStructuredWithImage(
			anyString(), anyString(), any(), any(), eq(PhotoAnswer.class)))
			.willReturn(new GeminiStructuredResult<>(new PhotoAnswer(List.of(items)), 0, 0));
	}

	@Nested
	@DisplayName("프롬프트")
	class Prompt {

		@Test
		@DisplayName("가게 메뉴를 'id: 이름' 줄로 넣는다 — 모델이 고를 수 있는 답은 이 목록뿐이다")
		void listsMenus() {
			givenAnswer();

			breadPhotoTagSuggester.suggest(MENUS, PHOTO);

			then(geminiClient).should().generateStructuredWithImage(
				promptCaptor.capture(), eq("image/jpeg"), any(), any(), eq(PhotoAnswer.class));
			assertThat(promptCaptor.getValue())
				.contains("101: 참깨 소금빵")
				.contains("202: 명란바게트")
				.contains("이 목록에서만 고른다")
				.doesNotContain("{menus}");
		}
	}

	@Nested
	@DisplayName("상자 → 좌표")
	class BoxToPoint {

		@Test
		@DisplayName("[ymin, xmin, ymax, xmax] 0~1000 을 중심점 0~1 로 접는다")
		void convertsToCenter() {
			givenAnswer(new Item(101L, "참깨 소금빵", List.of(200, 400, 600, 800)));

			List<RawTagSuggestion> suggestions = breadPhotoTagSuggester.suggest(MENUS, PHOTO);

			assertThat(suggestions).hasSize(1);
			assertThat(suggestions.get(0).x()).isEqualTo(0.6, Offset.offset(1e-9));
			assertThat(suggestions.get(0).y()).isEqualTo(0.4, Offset.offset(1e-9));
			assertThat(suggestions.get(0).boxArea()).isEqualTo(0.4 * 0.4, Offset.offset(1e-9));
		}

		@Test
		@DisplayName("좌표가 0~1000 을 벗어나도 0~1 안으로 접는다 — 태그 좌표는 그 범위여야 한다")
		void clampsOutOfRange() {
			givenAnswer(new Item(101L, "참깨 소금빵", List.of(-50, -100, 1200, 1500)));

			List<RawTagSuggestion> suggestions = breadPhotoTagSuggester.suggest(MENUS, PHOTO);

			assertThat(suggestions.get(0).x()).isBetween(0.0, 1.0);
			assertThat(suggestions.get(0).y()).isBetween(0.0, 1.0);
		}

		@Test
		@DisplayName("메뉴를 못 짚었거나 상자가 성하지 않으면 버린다")
		void dropsUnusableItems() {
			givenAnswer(
				new Item(null, "소금빵", List.of(0, 0, 100, 100)),
				new Item(101L, "참깨 소금빵", null),
				new Item(202L, "명란바게트", List.of(0, 0, 100)),
				new Item(202L, "명란바게트", List.of(0, 0, 100, 100)));

			List<RawTagSuggestion> suggestions = breadPhotoTagSuggester.suggest(MENUS, PHOTO);

			assertThat(suggestions).extracting(RawTagSuggestion::menuId).containsExactly(202L);
		}
	}

	@Nested
	@DisplayName("빈 응답과 실패")
	class Empty {

		@Test
		@DisplayName("items 가 비면 빈 목록 — 진열대 사진에 답하지 말라고 시킨 결과다")
		void emptyItems() {
			given(geminiClient.generateStructuredWithImage(
				anyString(), anyString(), any(), any(), eq(PhotoAnswer.class)))
				.willReturn(new GeminiStructuredResult<>(new PhotoAnswer(null), 0, 0));

			assertThat(breadPhotoTagSuggester.suggest(MENUS, PHOTO)).isEmpty();
		}

		@Test
		@DisplayName("Gemini 예외는 그대로 던진다 — 사진 단위로 살릴지는 부르는 쪽이 정한다")
		void propagatesGeminiException() {
			given(geminiClient.generateStructuredWithImage(
				anyString(), anyString(), any(), any(), eq(PhotoAnswer.class)))
				.willThrow(new GeminiException(GeminiErrorCode.RATE_LIMITED));

			assertThatThrownBy(() -> breadPhotoTagSuggester.suggest(MENUS, PHOTO))
				.isInstanceOf(GeminiException.class);
		}
	}
}
