package sweetgirl.croissant.world.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.world.dto.response.WorldCatalogResponse;
import sweetgirl.croissant.world.dto.response.WorldCharacterResponse;
import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.entity.WorldCharacter;
import sweetgirl.croissant.world.entity.WorldFixtures;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.WorldCatalogVersionRepository;
import sweetgirl.croissant.world.repository.WorldCharacterAssetRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.repository.WorldPlanetRepository;
import sweetgirl.croissant.world.repository.WorldPlanetStageRepository;

@ExtendWith(MockitoExtension.class)
class WorldCatalogServiceTest {

	@Mock
	private WorldPlanetRepository worldPlanetRepository;
	@Mock
	private WorldPlanetStageRepository worldPlanetStageRepository;
	@Mock
	private WorldCharacterRepository worldCharacterRepository;
	@Mock
	private WorldCharacterAssetRepository worldCharacterAssetRepository;
	@Mock
	private WorldCatalogVersionRepository worldCatalogVersionRepository;

	@InjectMocks
	private WorldCatalogService worldCatalogService;

	@BeforeEach
	void setCdnBaseUrl() {
		ReflectionTestUtils.setField(worldCatalogService, "cdnBaseUrl", "https://cdn.example.test");
	}

	private void givenCatalog() {
		given(worldPlanetRepository.findAllByActiveTrueOrderBySortOrderAscCodeAsc())
			.willReturn(List.of(
				WorldFixtures.planet("earth", "지구", 1, 10, null, 0, null),
				WorldFixtures.planet("mars", "화성", 2, 30, "earth", 1, "world/planets/mars_v1.png")));
		given(worldPlanetStageRepository.findAllByOrderByPlanetCodeAscStageOrderAsc())
			.willReturn(List.of(
				WorldFixtures.stage("earth", "village", "마을", 1, 0),
				WorldFixtures.stage("earth", "sky", "하늘", 2, 3),
				WorldFixtures.stage("mars", "village", "마을", 1, 0)));
		WorldCharacter dog = WorldFixtures.character("dog", "빵댕이", "earth", 1, true);
		dog.changeDescription("항상 신나지만 어딘가 허술한 강아지");
		given(worldCharacterRepository.findAllByActiveTrueOrderByPlanetCodeAscSortOrderAsc())
			.willReturn(List.of(dog, WorldFixtures.character("cat", "냥이", "earth", 2, false)));
		given(worldCharacterAssetRepository.findAllByOrderByIdCharacterCodeAscIdPoseAsc())
			.willReturn(List.of(WorldFixtures.asset("dog", "front", "world/characters/dog/front_v1.png")));
	}

	@Nested
	@DisplayName("카탈로그 조회")
	class GetCatalog {

		@Test
		@DisplayName("행성에 제 단계가 붙고, 캐릭터에는 올라간 포즈만 붙는다")
		void assemblesPlanetsAndCharacters() {
			given(worldCatalogVersionRepository.findCurrentVersion()).willReturn(Optional.of(7));
			givenCatalog();

			WorldCatalogResponse catalog = worldCatalogService.getCatalog();

			assertThat(catalog.version()).isEqualTo(7);
			assertThat(catalog.planets()).extracting(WorldPlanetResponse::code)
				.containsExactly("earth", "mars");
			assertThat(catalog.planets().get(0).stages()).hasSize(2);
			assertThat(catalog.planets().get(1).stages()).hasSize(1);
			assertThat(catalog.characters()).extracting(WorldCharacterResponse::code)
				.containsExactly("dog", "cat");
			assertThat(catalog.characters().get(0).assets())
				.containsExactly(org.assertj.core.api.Assertions.entry(
					"front", "https://cdn.example.test/world/characters/dog/front_v1.png"));
			assertThat(catalog.characters().get(1).assets()).isEmpty();
		}

		@Test
		@DisplayName("캐릭터 소개를 싣고, 없으면 null 로 둔다 — 앱이 번들 문구로 돌아간다")
		void carriesDescription() {
			given(worldCatalogVersionRepository.findCurrentVersion()).willReturn(Optional.of(1));
			givenCatalog();

			WorldCatalogResponse catalog = worldCatalogService.getCatalog();

			assertThat(catalog.characters()).extracting(WorldCharacterResponse::description)
				.containsExactly("항상 신나지만 어딘가 허술한 강아지", null);
		}

		@Test
		@DisplayName("image_key 가 비면 URL 도 비운다 — 앱이 번들 그림으로 돌아간다")
		void leavesImageUrlNullWhenKeyIsMissing() {
			given(worldCatalogVersionRepository.findCurrentVersion()).willReturn(Optional.of(1));
			givenCatalog();

			WorldCatalogResponse catalog = worldCatalogService.getCatalog();

			assertThat(catalog.planets().get(0).imageUrl()).isNull();
			assertThat(catalog.planets().get(1).imageUrl())
				.isEqualTo("https://cdn.example.test/world/planets/mars_v1.png");
		}

		@Test
		@DisplayName("버전이 그대로면 두 번째 호출은 본문을 다시 읽지 않는다")
		void reusesSnapshotWhileVersionIsUnchanged() {
			given(worldCatalogVersionRepository.findCurrentVersion()).willReturn(Optional.of(3));
			givenCatalog();

			WorldCatalogResponse first = worldCatalogService.getCatalog();
			WorldCatalogResponse second = worldCatalogService.getCatalog();

			assertThat(second).isSameAs(first);
			then(worldPlanetRepository).should(times(1))
				.findAllByActiveTrueOrderBySortOrderAscCodeAsc();
			then(worldCharacterRepository).should(times(1))
				.findAllByActiveTrueOrderByPlanetCodeAscSortOrderAsc();
			// 버전만은 매번 읽는다 — 다른 인스턴스가 올린 변경을 놓치지 않기 위해서다
			then(worldCatalogVersionRepository).should(times(2)).findCurrentVersion();
		}

		@Test
		@DisplayName("버전이 오르면 본문을 다시 읽는다")
		void reloadsWhenVersionMoves() {
			given(worldCatalogVersionRepository.findCurrentVersion())
				.willReturn(Optional.of(3), Optional.of(4));
			givenCatalog();

			WorldCatalogResponse first = worldCatalogService.getCatalog();
			WorldCatalogResponse second = worldCatalogService.getCatalog();

			assertThat(first.version()).isEqualTo(3);
			assertThat(second.version()).isEqualTo(4);
			then(worldPlanetRepository).should(times(2))
				.findAllByActiveTrueOrderBySortOrderAscCodeAsc();
		}

		@Test
		@DisplayName("버전 행이 없으면 예외이고 본문은 읽지 않는다")
		void throwsWhenVersionRowIsMissing() {
			given(worldCatalogVersionRepository.findCurrentVersion()).willReturn(Optional.empty());

			assertThatThrownBy(() -> worldCatalogService.getCatalog())
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("카탈로그가 준비되지 않았습니다");
			then(worldPlanetRepository).should(never())
				.findAllByActiveTrueOrderBySortOrderAscCodeAsc();
		}
	}
}
