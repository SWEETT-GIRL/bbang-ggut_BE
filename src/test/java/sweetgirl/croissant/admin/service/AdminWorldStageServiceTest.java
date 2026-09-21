package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.world.dto.request.AdminWorldStageCreateRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldStageUpdateRequest;
import sweetgirl.croissant.world.entity.WorldFixtures;
import sweetgirl.croissant.world.entity.WorldPlanetStage;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.WorldPlanetRepository;
import sweetgirl.croissant.world.repository.WorldPlanetStageRepository;
import sweetgirl.croissant.world.service.WorldCatalogVersionRaiser;

@ExtendWith(MockitoExtension.class)
class AdminWorldStageServiceTest {

	@Mock
	private WorldPlanetRepository worldPlanetRepository;
	@Mock
	private WorldPlanetStageRepository worldPlanetStageRepository;
	@Mock
	private WorldCatalogVersionRaiser worldCatalogVersionRaiser;

	@InjectMocks
	private AdminWorldStageService adminWorldStageService;

	/** 구간 길이가 10 인 지구. */
	private void givenEarth() {
		given(worldPlanetRepository.findById("earth"))
			.willReturn(Optional.of(WorldFixtures.planet("earth", "지구", 1, 10, null, 0, null)));
	}

	private WorldPlanetStage stage(Long id, int stageOrder, int offsetAt) {
		WorldPlanetStage stage = WorldFixtures.stage("earth", "sky", "하늘", stageOrder, offsetAt);
		ReflectionTestUtils.setField(stage, "id", id);
		return stage;
	}

	@Test
	@DisplayName("단계를 더하면 카탈로그 번호가 오른다")
	void createRaisesCatalogVersion() {
		givenEarth();
		given(worldPlanetStageRepository.findAllByPlanetCodeOrderByStageOrderAsc("earth"))
			.willReturn(List.of());
		given(worldPlanetStageRepository.save(any())).willAnswer(call -> {
			WorldPlanetStage saved = call.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 5L);
			return saved;
		});

		Long id = adminWorldStageService.create("earth",
			new AdminWorldStageCreateRequest("sky", "하늘", 2, 3, null));

		assertThat(id).isEqualTo(5L);
		then(worldCatalogVersionRaiser).should().raise();
	}

	@Test
	@DisplayName("상대 위치가 그 행성 구간을 넘으면 막는다 — 아무도 못 닿는 단계가 된다")
	void rejectsOffsetBeyondSpan() {
		givenEarth();

		assertThatThrownBy(() -> adminWorldStageService.create("earth",
			new AdminWorldStageCreateRequest("space", "우주", 3, 10, null)))
			.isInstanceOf(WorldException.class)
			.hasMessageContaining("고도 단계 값이 올바르지 않습니다");
		then(worldCatalogVersionRaiser).should(never()).raise();
	}

	@Test
	@DisplayName("같은 행성에서 순서가 겹치면 막는다")
	void rejectsDuplicateOrder() {
		givenEarth();
		given(worldPlanetStageRepository.findAllByPlanetCodeOrderByStageOrderAsc("earth"))
			.willReturn(List.of(stage(1L, 2, 3)));

		assertThatThrownBy(() -> adminWorldStageService.create("earth",
			new AdminWorldStageCreateRequest("cloud", "구름", 2, 5, null)))
			.isInstanceOf(WorldException.class);
	}

	@Test
	@DisplayName("자기 순서는 그대로 둔 채 고칠 수 있다")
	void allowsKeepingOwnOrder() {
		WorldPlanetStage sky = stage(1L, 2, 3);
		given(worldPlanetStageRepository.findById(1L)).willReturn(Optional.of(sky));
		given(worldPlanetStageRepository.findAllByPlanetCodeOrderByStageOrderAsc("earth"))
			.willReturn(List.of(sky));

		adminWorldStageService.update(1L,
			new AdminWorldStageUpdateRequest(null, "하늘길", 2, null, null));

		assertThat(sky.getName()).isEqualTo("하늘길");
		then(worldCatalogVersionRaiser).should().raise();
	}

	@Test
	@DisplayName("없는 단계를 지우려 하면 예외다")
	void rejectsDeletingUnknownStage() {
		given(worldPlanetStageRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> adminWorldStageService.delete(99L))
			.isInstanceOf(WorldException.class)
			.hasMessageContaining("그런 고도 단계가 없습니다");
	}
}
