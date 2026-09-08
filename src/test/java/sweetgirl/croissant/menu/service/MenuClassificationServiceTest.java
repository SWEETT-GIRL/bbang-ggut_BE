package sweetgirl.croissant.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.menu.config.MenuClassificationProperties;
import sweetgirl.croissant.menu.dto.classification.MenuClassificationBatchResult;
import sweetgirl.croissant.menu.dto.classification.MenuKindVerdict;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuNameClassification;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.repository.MenuNameClassificationRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.entity.Store;

@ExtendWith(MockitoExtension.class)
class MenuClassificationServiceTest {

	@Mock
	private MenuRepository menuRepository;
	@Mock
	private MenuNameClassificationRepository menuNameClassificationRepository;
	@Mock
	private MenuKindClassifier menuKindClassifier;
	@Mock
	private MenuClassificationCommandService commandService;

	private MenuClassificationService service;

	@BeforeEach
	void setUp() {
		MenuClassificationProperties properties = new MenuClassificationProperties();
		properties.setBatchSize(100);
		service = new MenuClassificationService(
			menuRepository, menuNameClassificationRepository, menuKindClassifier, commandService, properties);
	}

	private static Menu menu(long id, String name) {
		Menu menu = Menu.builder()
			.store(Store.builder().name("빵집").address("서울 성동구").build())
			.name(name)
			.build();
		ReflectionTestUtils.setField(menu, "id", id);
		return menu;
	}

	@Test
	@DisplayName("대기 메뉴가 없으면 AI 도 적용도 부르지 않는다")
	void nothingToDo() {
		given(menuRepository.findAwaitingClassification(any(Pageable.class))).willReturn(List.of());

		MenuClassificationBatchResult result = service.classifyNextBatch();

		assertThat(result.total()).isZero();
		verify(menuKindClassifier, never()).classify(anyMap());
		verify(commandService, never()).apply(anyList(), anyMap(), anyList(), anyString());
	}

	@Test
	@DisplayName("캐시에 있는 이름은 빼고, 없는 이름만 AI 에 묻고, 둘을 합쳐 적용한다")
	void cacheHitsSkipAi() {
		given(menuRepository.findAwaitingClassification(any(Pageable.class)))
			.willReturn(List.of(menu(1L, "소금빵"), menu(2L, "사각 카스테라(초코)")));
		MenuKindVerdict cachedSalt = MenuKindVerdict.agreed("소금빵", MenuKind.소금빵, 1.0);
		given(menuNameClassificationRepository.findAllById(any()))
			.willReturn(List.of(MenuNameClassification.from(cachedSalt, "m")));
		MenuKindVerdict freshCastella = MenuKindVerdict.agreed("사각카스테라초코", MenuKind.카스테라, 0.95);
		given(menuKindClassifier.classify(anyMap())).willReturn(List.of(freshCastella));
		given(menuKindClassifier.modelName()).willReturn("gemini-3.5-flash-lite");

		MenuClassificationBatchResult result = service.classifyNextBatch();

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> asked = ArgumentCaptor.forClass(Map.class);
		verify(menuKindClassifier).classify(asked.capture());
		assertThat(asked.getValue()).containsExactly(Map.entry("사각카스테라초코", "사각 카스테라(초코)"));

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, MenuKindVerdict>> applied = ArgumentCaptor
			.forClass(Map.class);
		verify(commandService).apply(eq(List.of(1L, 2L)), applied.capture(), eq(List.of(freshCastella)),
			eq("gemini-3.5-flash-lite"));
		assertThat(applied.getValue()).containsKeys("소금빵", "사각카스테라초코");

		assertThat(result.total()).isEqualTo(2);
		assertThat(result.cacheHits()).isEqualTo(1);
		assertThat(result.aiClassified()).isEqualTo(1);
		assertThat(result.agreed()).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 정규화 키의 메뉴가 여럿이면 한 번만 묻고, 프롬프트엔 처음 본 원본 이름을 쓴다")
	void duplicateKeysAskedOnce() {
		given(menuRepository.findAwaitingClassification(any(Pageable.class)))
			.willReturn(List.of(menu(1L, "소금 빵!"), menu(2L, "소금빵"), menu(3L, "소금-빵")));
		given(menuNameClassificationRepository.findAllById(any())).willReturn(List.of());
		given(menuKindClassifier.classify(anyMap()))
			.willReturn(List.of(MenuKindVerdict.agreed("소금빵", MenuKind.소금빵, 1.0)));
		given(menuKindClassifier.modelName()).willReturn("m");

		MenuClassificationBatchResult result = service.classifyNextBatch();

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> asked = ArgumentCaptor.forClass(Map.class);
		verify(menuKindClassifier).classify(asked.capture());
		assertThat(asked.getValue()).containsExactly(Map.entry("소금빵", "소금 빵!"));
		assertThat(result.total()).isEqualTo(3);
		assertThat(result.aiClassified()).isEqualTo(1);
	}

	@Test
	@DisplayName("결과 집계 — 확정·비-빵·미결을 나눠 센다")
	void countsVerdictKinds() {
		given(menuRepository.findAwaitingClassification(any(Pageable.class)))
			.willReturn(List.of(menu(1L, "카스테라"), menu(2L, "허니레몬차"), menu(3L, "호두랑 호두")));
		given(menuNameClassificationRepository.findAllById(any())).willReturn(List.of());
		given(menuKindClassifier.classify(anyMap())).willReturn(List.of(
			MenuKindVerdict.agreed("카스테라", MenuKind.카스테라, 1.0),
			MenuKindVerdict.nonBakery("허니레몬차", 0.9),
			MenuKindVerdict.undecided("호두랑호두", 0.5)));
		given(menuKindClassifier.modelName()).willReturn("m");

		MenuClassificationBatchResult result = service.classifyNextBatch();

		assertThat(result.agreed()).isEqualTo(1);
		assertThat(result.nonBakery()).isEqualTo(1);
		assertThat(result.undecided()).isEqualTo(1);
	}
}
