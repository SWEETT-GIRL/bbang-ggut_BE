package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.config.BreadPhotoTagProperties;
import sweetgirl.croissant.breadlog.dto.analysis.BreadPhotoToAnalyze;
import sweetgirl.croissant.breadlog.dto.analysis.RawTagSuggestion;
import sweetgirl.croissant.breadlog.dto.response.BreadPhotoAnalyzeResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadPhotoAnalyzeResponse.PhotoSuggestions;
import sweetgirl.croissant.breadlog.dto.response.BreadPhotoAnalyzeResponse.TagSuggestion;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.external.gemini.exception.GeminiErrorCode;
import sweetgirl.croissant.external.gemini.exception.GeminiException;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.repository.MenuRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("빵로그 사진 태그 제안")
class BreadPhotoTagSuggestionServiceTest {

	private static final Long STORE_ID = 472221L;

	@Mock
	private MenuRepository menuRepository;

	@Mock
	private BreadPhotoTagSuggester breadPhotoTagSuggester;

	@Mock
	private BreadPhotoTagRateLimiter breadPhotoTagRateLimiter;

	private ExecutorService executor;
	private BreadPhotoTagProperties properties;
	private BreadPhotoTagSuggestionService breadPhotoTagSuggestionService;

	@BeforeEach
	void setUp() {
		executor = Executors.newFixedThreadPool(4);
		properties = new BreadPhotoTagProperties();
		properties.setEnabled(true);
		breadPhotoTagSuggestionService = new BreadPhotoTagSuggestionService(
			menuRepository, breadPhotoTagSuggester, breadPhotoTagRateLimiter, executor, properties);
	}

	@AfterEach
	void tearDown() {
		executor.shutdownNow();
	}

	private static Menu menu(Long id, String name) {
		Menu menu = Menu.builder().name(name).build();
		ReflectionTestUtils.setField(menu, "id", id);
		return menu;
	}

	private static BreadPhotoToAnalyze photo(String photoKey) {
		return new BreadPhotoToAnalyze(photoKey, "image/jpeg", new byte[] {1, 2, 3});
	}

	/** 상자 넓이를 area 로 바로 주기 위한 도우미 — 좌표 자체는 이 테스트의 관심사가 아니다 */
	private static RawTagSuggestion suggestion(Long menuId, double area) {
		return new RawTagSuggestion(menuId, 0.5, 0.5, area);
	}

	/** Mockito 가 스텁을 등록할 때 matcher 를 null 인자에도 한 번 적용한다 — 거기서 터지지 않게 */
	private static boolean hasKey(BreadPhotoToAnalyze photo, String photoKey) {
		return photo != null && photoKey.equals(photo.photoKey());
	}

	private void givenMenus(Menu... menus) {
		given(menuRepository.findByStoreIdOrderByLikeCountDesc(anyLong(), any(Pageable.class)))
			.willReturn(List.of(menus));
	}

	private List<TagSuggestion> analyzeOnePhoto() {
		BreadPhotoAnalyzeResponse response = breadPhotoTagSuggestionService.analyze(STORE_ID, List.of(photo("l0")));
		return response.results().get(0).suggestions();
	}

	@Nested
	@DisplayName("응답 접기")
	class Folding {

		@Test
		@DisplayName("그 가게 메뉴가 아닌 menuId 는 버린다 — 목록 밖 답은 환각이다")
		void dropsMenuIdOutsideStore() {
			givenMenus(menu(1L, "소금빵"));
			given(breadPhotoTagSuggester.suggest(any(), any()))
				.willReturn(List.of(suggestion(1L, 0.4), suggestion(999L, 0.9)));

			List<TagSuggestion> suggestions = analyzeOnePhoto();

			assertThat(suggestions).extracting(TagSuggestion::menuId).containsExactly(1L);
		}

		@Test
		@DisplayName("같은 메뉴가 여러 번 오면 상자가 가장 큰 것 하나만 남긴다")
		void keepsLargestBoxPerMenu() {
			givenMenus(menu(1L, "크루아상"));
			given(breadPhotoTagSuggester.suggest(any(), any()))
				.willReturn(List.of(
					suggestion(1L, 0.10),
					new RawTagSuggestion(1L, 0.7, 0.3, 0.50),
					suggestion(1L, 0.20)));

			List<TagSuggestion> suggestions = analyzeOnePhoto();

			assertThat(suggestions).hasSize(1);
			assertThat(suggestions.get(0).x()).isEqualTo(0.7, Offset.offset(1e-9));
			assertThat(suggestions.get(0).y()).isEqualTo(0.3, Offset.offset(1e-9));
		}

		@Test
		@DisplayName("상한을 넘으면 상자가 큰 것부터 5개만 남긴다")
		void limitsToMaxSuggestions() {
			givenMenus(menu(1L, "a"), menu(2L, "b"), menu(3L, "c"), menu(4L, "d"), menu(5L, "e"), menu(6L, "f"));
			given(breadPhotoTagSuggester.suggest(any(), any()))
				.willReturn(List.of(
					suggestion(1L, 0.10), suggestion(2L, 0.60), suggestion(3L, 0.30),
					suggestion(4L, 0.50), suggestion(5L, 0.20), suggestion(6L, 0.40)));

			List<TagSuggestion> suggestions = analyzeOnePhoto();

			assertThat(suggestions).hasSize(properties.getMaxSuggestions());
			assertThat(suggestions).extracting(TagSuggestion::menuId).containsExactly(2L, 4L, 6L, 3L, 5L);
		}

		@Test
		@DisplayName("이름은 모델의 답이 아니라 DB 의 메뉴 이름을 쓴다")
		void namesComeFromDatabase() {
			givenMenus(menu(7L, "참깨 소금빵"));
			given(breadPhotoTagSuggester.suggest(any(), any())).willReturn(List.of(suggestion(7L, 0.5)));

			assertThat(analyzeOnePhoto()).extracting(TagSuggestion::name).containsExactly("참깨 소금빵");
		}
	}

	@Nested
	@DisplayName("부르지 않거나, 실패해도 나머지는 산다")
	class Resilience {

		@Test
		@DisplayName("메뉴가 하나도 없는 가게면 Gemini 를 부르지 않고 빈 결과를 돌려준다")
		void skipsWhenStoreHasNoMenu() {
			given(menuRepository.findByStoreIdOrderByLikeCountDesc(anyLong(), any(Pageable.class)))
				.willReturn(List.of());

			BreadPhotoAnalyzeResponse response = breadPhotoTagSuggestionService.analyze(
				STORE_ID, List.of(photo("l0"), photo("l1")));

			assertThat(response.results()).extracting(PhotoSuggestions::photoKey).containsExactly("l0", "l1");
			assertThat(response.results()).allSatisfy(result -> assertThat(result.suggestions()).isEmpty());
			then(breadPhotoTagSuggester).should(never()).suggest(any(), any());
		}

		@Test
		@DisplayName("한 사진이 실패해도 그 사진만 비고 나머지 사진 결과는 살아남는다")
		void oneFailureDoesNotSinkTheRest() {
			givenMenus(menu(1L, "소금빵"));
			// 사진마다 병렬로 부르므로 호출 순서로 스텁하면 흔들린다 — 어느 사진인지로 가른다
			given(breadPhotoTagSuggester.suggest(any(), argThat(photo -> hasKey(photo, "l0"))))
				.willThrow(new GeminiException(GeminiErrorCode.UNAVAILABLE));
			given(breadPhotoTagSuggester.suggest(any(), argThat(photo -> hasKey(photo, "l1"))))
				.willReturn(List.of(suggestion(1L, 0.5)));

			BreadPhotoAnalyzeResponse response = breadPhotoTagSuggestionService.analyze(
				STORE_ID, List.of(photo("l0"), photo("l1")));

			assertThat(response.results()).hasSize(2);
			assertThat(response.results().get(0).suggestions()).isEmpty();
			assertThat(response.results().get(1).suggestions())
				.extracting(TagSuggestion::menuId).containsExactly(1L);
		}

		@Test
		@DisplayName("사진이 많아 풀에 자리가 없어도 나머지 사진은 결과를 받는다")
		void survivesRejectedExecution() {
			givenMenus(menu(1L, "소금빵"));
			executor.shutdown();

			BreadPhotoAnalyzeResponse response = breadPhotoTagSuggestionService.analyze(
				STORE_ID, List.of(photo("l0"), photo("l1")));

			assertThat(response.results()).hasSize(2);
			assertThat(response.results()).allSatisfy(result -> assertThat(result.suggestions()).isEmpty());
			then(breadPhotoTagSuggester).should(never()).suggest(any(), any());
		}

		@Test
		@DisplayName("사진 키는 보낸 그대로 돌려준다 — 병렬 호출이라 순번으로는 되짚을 수 없다")
		void echoesPhotoKeys() {
			givenMenus(menu(1L, "소금빵"));
			given(breadPhotoTagSuggester.suggest(any(), any())).willReturn(List.of());

			BreadPhotoAnalyzeResponse response = breadPhotoTagSuggestionService.analyze(
				STORE_ID, List.of(photo("l0"), photo("s12"), photo("l3")));

			assertThat(response.results())
				.extracting(PhotoSuggestions::photoKey).containsExactly("l0", "s12", "l3");
		}
	}

	@Nested
	@DisplayName("앱이 부르는 자리 — 플래그와 레이트리밋")
	class Gate {

		private static final Long MEMBER_ID = 7L;

		@Test
		@DisplayName("기능이 꺼져 있으면 Gemini 도 메뉴 조회도 하지 않고 빈 결과를 준다")
		void disabledReturnsEmpty() {
			properties.setEnabled(false);

			BreadPhotoAnalyzeResponse response = breadPhotoTagSuggestionService.analyzeForMember(
				MEMBER_ID, STORE_ID, List.of(photo("l0")));

			assertThat(response.results()).hasSize(1);
			assertThat(response.results().get(0).suggestions()).isEmpty();
			then(breadPhotoTagSuggester).should(never()).suggest(any(), any());
			then(menuRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("꺼져 있어도 요청 자체는 본다 — 켜는 날 갑자기 400 이 나면 안 된다")
		void validatesEvenWhenDisabled() {
			properties.setEnabled(false);
			BreadPhotoToAnalyze tooLarge = new BreadPhotoToAnalyze(
				"l0", "image/jpeg", new byte[(int)properties.getMaxPhotoSize().toBytes() + 1]);

			assertThatThrownBy(() -> breadPhotoTagSuggestionService.analyzeForMember(
				MEMBER_ID, STORE_ID, List.of(tooLarge)))
				.isInstanceOf(BreadLogException.class);
		}

		@Test
		@DisplayName("레이트리밋에 걸리면 429 예외 — Gemini 는 부르지 않는다")
		void rateLimited() {
			given(breadPhotoTagRateLimiter.tryAcquire(MEMBER_ID)).willReturn(false);

			assertThatThrownBy(() -> breadPhotoTagSuggestionService.analyzeForMember(
				MEMBER_ID, STORE_ID, List.of(photo("l0"))))
				.isInstanceOf(BreadLogException.class)
				.extracting("errorCode").isEqualTo(BreadLogErrorCode.PHOTO_ANALYZE_RATE_LIMITED);
			then(breadPhotoTagSuggester).should(never()).suggest(any(), any());
		}

		@Test
		@DisplayName("켜져 있고 한도 안이면 분석까지 간다")
		void passesThrough() {
			given(breadPhotoTagRateLimiter.tryAcquire(MEMBER_ID)).willReturn(true);
			givenMenus(menu(1L, "소금빵"));
			given(breadPhotoTagSuggester.suggest(any(), any())).willReturn(List.of(suggestion(1L, 0.5)));

			BreadPhotoAnalyzeResponse response = breadPhotoTagSuggestionService.analyzeForMember(
				MEMBER_ID, STORE_ID, List.of(photo("l0")));

			assertThat(response.results().get(0).suggestions())
				.extracting(TagSuggestion::menuId).containsExactly(1L);
		}
	}
}
