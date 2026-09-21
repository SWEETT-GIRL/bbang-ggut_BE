package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.world.dto.request.AdminWorldPlanetCreateRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldPlanetUpdateRequest;
import sweetgirl.croissant.world.dto.response.AdminWorldPlanetResponse;
import sweetgirl.croissant.world.entity.WorldFixtures;
import sweetgirl.croissant.world.entity.WorldPlanet;
import sweetgirl.croissant.world.enums.PlanetGroup;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.WorldPlanetRepository;
import sweetgirl.croissant.world.service.WorldCatalogVersionRaiser;

@ExtendWith(MockitoExtension.class)
class AdminWorldPlanetServiceTest {

	@Mock
	private WorldPlanetRepository worldPlanetRepository;
	@Mock
	private WorldCatalogVersionRaiser worldCatalogVersionRaiser;

	@InjectMocks
	private AdminWorldPlanetService adminWorldPlanetService;

	private static final List<WorldPlanet> SOLAR = List.of(
		WorldFixtures.planet("earth", "지구", 1, 10, null, 0, null),
		WorldFixtures.planet("mars", "화성", 2, 30, "earth", 1, null),
		WorldFixtures.planet("jupiter", "목성", 3, 40, "mars", 1, null));

	private AdminWorldPlanetCreateRequest createRequest(String code, String unlockAfter) {
		return new AdminWorldPlanetCreateRequest(
			code, code, PlanetGroup.SOLAR, 4, 60, unlockAfter, false, 1, 2, true, null);
	}

	@Nested
	@DisplayName("행성 목록")
	class GetPlanets {

		@Test
		@DisplayName("여정을 따라 누적한 도착점을 함께 내린다")
		void reportsArrivePoints() {
			given(worldPlanetRepository.findAll()).willReturn(SOLAR);

			List<AdminWorldPlanetResponse> planets = adminWorldPlanetService.getPlanets();

			assertThat(planets)
				.extracting(AdminWorldPlanetResponse::code, AdminWorldPlanetResponse::arriveAt)
				.containsExactly(
					org.assertj.core.groups.Tuple.tuple("earth", 0),
					org.assertj.core.groups.Tuple.tuple("mars", 10),
					org.assertj.core.groups.Tuple.tuple("jupiter", 40));
		}
	}

	@Nested
	@DisplayName("행성 추가")
	class Create {

		@Test
		@DisplayName("만들고 나면 카탈로그 번호가 오른다 — 안 오르면 앱이 옛 카탈로그를 계속 쓴다")
		void raisesCatalogVersion() {
			given(worldPlanetRepository.existsById("saturn")).willReturn(false);
			given(worldPlanetRepository.findAll()).willReturn(SOLAR);

			adminWorldPlanetService.create(createRequest("saturn", "jupiter"));

			then(worldPlanetRepository).should().save(org.mockito.ArgumentMatchers.any());
			then(worldCatalogVersionRaiser).should().raise();
		}

		@Test
		@DisplayName("이미 쓰는 코드면 막는다")
		void rejectsDuplicateCode() {
			given(worldPlanetRepository.existsById("mars")).willReturn(true);

			assertThatThrownBy(() -> adminWorldPlanetService.create(createRequest("mars", "earth")))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("이미 쓰고 있는 행성 코드");
			then(worldCatalogVersionRaiser).should(never()).raise();
		}

		@Test
		@DisplayName("자기 자신을 앞 행성으로 두면 막는다")
		void rejectsSelfReference() {
			given(worldPlanetRepository.existsById("saturn")).willReturn(false);

			assertThatThrownBy(() -> adminWorldPlanetService.create(createRequest("saturn", "saturn")))
				.isInstanceOf(WorldException.class);
		}

		@Test
		@DisplayName("없는 행성을 앞에 두면 막는다")
		void rejectsUnknownPrevious() {
			given(worldPlanetRepository.existsById("saturn")).willReturn(false);
			given(worldPlanetRepository.findAll()).willReturn(SOLAR);

			assertThatThrownBy(() -> adminWorldPlanetService.create(createRequest("saturn", "pluto")))
				.isInstanceOf(WorldException.class);
		}
	}

	@Nested
	@DisplayName("행성 수정")
	class Update {

		@Test
		@DisplayName("사슬이 자기에게 되돌아오면 막는다 — 여정이 끊긴다")
		void rejectsCycle() {
			given(worldPlanetRepository.findById("earth"))
				.willReturn(Optional.of(WorldFixtures.planet("earth", "지구", 1, 10, null, 0, null)));
			given(worldPlanetRepository.findAll()).willReturn(SOLAR);

			// earth 의 앞을 jupiter 로 두면 earth → jupiter → mars → earth 로 돈다
			AdminWorldPlanetUpdateRequest request = new AdminWorldPlanetUpdateRequest(
				null, null, null, "jupiter", null, null, null, null, null, null);

			assertThatThrownBy(() -> adminWorldPlanetService.update("earth", request))
				.isInstanceOf(WorldException.class);
			then(worldCatalogVersionRaiser).should(never()).raise();
		}

		@Test
		@DisplayName("비워 보낸 칸은 그대로 두고, 고친 뒤 카탈로그 번호를 올린다")
		void keepsUntouchedFields() {
			WorldPlanet mars = WorldFixtures.planet("mars", "화성", 2, 30, "earth", 1, null);
			given(worldPlanetRepository.findById("mars")).willReturn(Optional.of(mars));

			adminWorldPlanetService.update("mars", new AdminWorldPlanetUpdateRequest(
				null, null, 25, null, null, null, 3, null, null, null));

			assertThat(mars.getName()).isEqualTo("화성");
			assertThat(mars.getSpan()).isEqualTo(25);
			assertThat(mars.getAdsPerWake()).isEqualTo(3);
			then(worldCatalogVersionRaiser).should().raise();
		}
	}
}
