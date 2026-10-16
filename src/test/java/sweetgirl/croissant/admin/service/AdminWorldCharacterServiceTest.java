package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.world.dto.request.AdminWorldCharacterCreateRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldCharacterUpdateRequest;
import sweetgirl.croissant.world.entity.WorldCharacter;
import sweetgirl.croissant.world.entity.WorldFixtures;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.repository.WorldCharacterAssetRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.repository.WorldPlanetRepository;
import sweetgirl.croissant.world.service.WorldCatalogVersionRaiser;

@ExtendWith(MockitoExtension.class)
class AdminWorldCharacterServiceTest {

	@Mock
	private WorldCharacterRepository worldCharacterRepository;
	@Mock
	private WorldCharacterAssetRepository worldCharacterAssetRepository;
	@Mock
	private WorldPlanetRepository worldPlanetRepository;
	@Mock
	private MemberCharacterRepository memberCharacterRepository;
	@Mock
	private WorldCatalogVersionRaiser worldCatalogVersionRaiser;

	@InjectMocks
	private AdminWorldCharacterService adminWorldCharacterService;

	@Nested
	@DisplayName("마스코트는 반드시 한 명")
	class Mascot {

		@Test
		@DisplayName("지금 마스코트를 내리려 하면 막는다 — 빈자리가 되면 신규 회원이 홈을 못 연다")
		void rejectsDroppingTheOnlyMascot() {
			given(worldCharacterRepository.findById("dog"))
				.willReturn(Optional.of(WorldFixtures.character("dog", "빵댕이", "earth", 1, true)));

			assertThatThrownBy(() -> adminWorldCharacterService.update("dog",
				new AdminWorldCharacterUpdateRequest(null, null, null, null, false, null)))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("반드시 한 명");
			then(worldCatalogVersionRaiser).should(never()).raise();
		}

		@Test
		@DisplayName("마스코트를 비활성으로 돌리려 해도 막는다")
		void rejectsDeactivatingTheMascot() {
			given(worldCharacterRepository.findById("dog"))
				.willReturn(Optional.of(WorldFixtures.character("dog", "빵댕이", "earth", 1, true)));

			assertThatThrownBy(() -> adminWorldCharacterService.update("dog",
				new AdminWorldCharacterUpdateRequest(null, null, null, null, null, false)))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("반드시 한 명");
		}

		@Test
		@DisplayName("다른 캐릭터를 마스코트로 세우면 지금 마스코트를 먼저 내린다")
		void clearsTheOldMascotFirst() {
			WorldCharacter cat = WorldFixtures.character("cat", "냥이", "earth", 2, false);
			given(worldCharacterRepository.findById("cat")).willReturn(Optional.of(cat));

			adminWorldCharacterService.update("cat",
				new AdminWorldCharacterUpdateRequest(null, null, null, null, true, null));

			// 부분 유니크 인덱스가 둘을 못 버틴다 — 세우기 전에 내려야 한다
			then(worldCharacterRepository).should().clearMascot();
			assertThat(cat.isMascot()).isTrue();
		}

		@Test
		@DisplayName("마스코트로 세울 땐 내린 뒤 다시 읽은 캐릭터에 변경을 쓴다 — 내리는 벌크 UPDATE 가 먼저 읽은 것을 준영속으로 만든다")
		void writesToTheCharacterReloadedAfterClearing() {
			WorldCharacter staleCat = WorldFixtures.character("cat", "냥이", "earth", 2, false);
			WorldCharacter freshCat = WorldFixtures.character("cat", "냥이", "earth", 2, false);
			given(worldCharacterRepository.findById("cat"))
				.willReturn(Optional.of(staleCat), Optional.of(freshCat));

			adminWorldCharacterService.update("cat",
				new AdminWorldCharacterUpdateRequest("새 냥이", null, null, null, true, null));

			InOrder inOrder = inOrder(worldCharacterRepository);
			inOrder.verify(worldCharacterRepository).clearMascot();
			inOrder.verify(worldCharacterRepository).findById("cat");
			assertThat(freshCat.isMascot()).isTrue();
			assertThat(freshCat.getName()).isEqualTo("새 냥이");
			assertThat(staleCat.isMascot()).isFalse();
		}

		@Test
		@DisplayName("마스코트가 아닌 캐릭터는 비활성으로 돌릴 수 있다")
		void allowsDeactivatingOthers() {
			WorldCharacter cat = WorldFixtures.character("cat", "냥이", "earth", 2, false);
			given(worldCharacterRepository.findById("cat")).willReturn(Optional.of(cat));

			adminWorldCharacterService.update("cat",
				new AdminWorldCharacterUpdateRequest(null, null, null, null, null, false));

			assertThat(cat.isActive()).isFalse();
			then(worldCatalogVersionRaiser).should().raise();
		}
	}

	@Nested
	@DisplayName("캐릭터 추가")
	class Create {

		@Test
		@DisplayName("없는 행성에 배정하면 막는다")
		void rejectsUnknownPlanet() {
			given(worldCharacterRepository.existsById("otter")).willReturn(false);
			given(worldPlanetRepository.existsById("pluto")).willReturn(false);

			assertThatThrownBy(() -> adminWorldCharacterService.create(
				new AdminWorldCharacterCreateRequest("otter", "수달", null, "pluto", 3, false)))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("그런 행성이 없습니다");
		}

		@Test
		@DisplayName("이미 쓰는 코드면 막는다")
		void rejectsDuplicateCode() {
			given(worldCharacterRepository.existsById("dog")).willReturn(true);

			assertThatThrownBy(() -> adminWorldCharacterService.create(
				new AdminWorldCharacterCreateRequest("dog", "빵댕이", null, "earth", 1, false)))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("이미 쓰고 있는 캐릭터 코드");
		}

		@Test
		@DisplayName("만들고 나면 카탈로그 번호가 오른다")
		void raisesCatalogVersion() {
			given(worldCharacterRepository.existsById("otter")).willReturn(false);
			given(worldPlanetRepository.existsById("jupiter")).willReturn(true);

			adminWorldCharacterService.create(
				new AdminWorldCharacterCreateRequest("otter", "수달", null, "jupiter", 3, false));

			then(worldCharacterRepository).should().save(any());
			then(worldCatalogVersionRaiser).should().raise();
		}
	}

	@Nested
	@DisplayName("한 줄 소개")
	class Description {

		@Test
		@DisplayName("만들 때 소개를 함께 저장한다")
		void savesDescriptionOnCreate() {
			given(worldCharacterRepository.existsById("otter")).willReturn(false);
			given(worldPlanetRepository.existsById("jupiter")).willReturn(true);

			adminWorldCharacterService.create(
				new AdminWorldCharacterCreateRequest("otter", "수달", " 장난꾸러기 수달 ", "jupiter", 3, false));

			then(worldCharacterRepository).should()
				.save(argThat(character -> "장난꾸러기 수달".equals(character.getDescription())));
		}

		@Test
		@DisplayName("소개를 보내면 바꾸고 카탈로그 번호를 올린다")
		void changesDescription() {
			WorldCharacter cat = WorldFixtures.character("cat", "냥이", "earth", 2, false);
			given(worldCharacterRepository.findById("cat")).willReturn(Optional.of(cat));

			adminWorldCharacterService.update("cat",
				new AdminWorldCharacterUpdateRequest(null, "꼬질한 고양이", null, null, null, null));

			assertThat(cat.getDescription()).isEqualTo("꼬질한 고양이");
			then(worldCatalogVersionRaiser).should().raise();
		}

		@Test
		@DisplayName("소개를 비워 보내지 않으면(null) 지금 소개를 그대로 둔다")
		void keepsDescriptionWhenNull() {
			WorldCharacter cat = WorldFixtures.character("cat", "냥이", "earth", 2, false);
			cat.changeDescription("꼬질한 고양이");
			given(worldCharacterRepository.findById("cat")).willReturn(Optional.of(cat));

			adminWorldCharacterService.update("cat",
				new AdminWorldCharacterUpdateRequest("야옹이", null, null, null, null, null));

			assertThat(cat.getDescription()).isEqualTo("꼬질한 고양이");
		}

		@Test
		@DisplayName("빈 문자열을 보내면 소개를 지운다 — 앱이 번들 문구로 돌아간다")
		void clearsDescriptionWhenBlank() {
			WorldCharacter cat = WorldFixtures.character("cat", "냥이", "earth", 2, false);
			cat.changeDescription("꼬질한 고양이");
			given(worldCharacterRepository.findById("cat")).willReturn(Optional.of(cat));

			adminWorldCharacterService.update("cat",
				new AdminWorldCharacterUpdateRequest(null, "", null, null, null, null));

			assertThat(cat.getDescription()).isNull();
		}
	}
}
