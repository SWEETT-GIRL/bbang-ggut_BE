package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.world.dto.request.AdminWorldAssetRegisterRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldAssetUploadRequest;
import sweetgirl.croissant.world.entity.WorldCharacterAsset;
import sweetgirl.croissant.world.entity.WorldFixtures;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.WorldCharacterAssetRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.service.WorldCatalogVersionRaiser;

@ExtendWith(MockitoExtension.class)
class AdminWorldAssetServiceTest {

	@Mock
	private WorldCharacterRepository worldCharacterRepository;
	@Mock
	private WorldCharacterAssetRepository worldCharacterAssetRepository;
	@Mock
	private S3Service s3Service;
	@Mock
	private WorldCatalogVersionRaiser worldCatalogVersionRaiser;

	@InjectMocks
	private AdminWorldAssetService adminWorldAssetService;

	private String uploadKeyFor(String pose) {
		given(worldCharacterRepository.existsById("dog")).willReturn(true);
		given(s3Service.generatePresignedUrlForKey(any(), any()))
			.willAnswer(call -> new PresignedUrlResponse("f", "image/png", "https://s3", call.getArgument(0)));

		return adminWorldAssetService
			.createUploadUrl("dog", new AdminWorldAssetUploadRequest(pose, "image/png"))
			.awsKey();
	}

	@Test
	@DisplayName("처음 올리는 포즈는 v1 로 자리를 잡는다")
	void startsAtVersionOne() {
		given(worldCharacterAssetRepository.findById(any())).willReturn(Optional.empty());

		assertThat(uploadKeyFor("front")).isEqualTo("world/characters/dog/front_v1.png");
	}

	@Test
	@DisplayName("이미 올라간 포즈는 다음 번호를 받는다 — 같은 키를 덮으면 옛 그림이 남는다")
	void bumpsVersion() {
		given(worldCharacterAssetRepository.findById(any())).willReturn(Optional.of(
			WorldFixtures.asset("dog", "front", "world/characters/dog/front_v3.png")));

		assertThat(uploadKeyFor("front")).isEqualTo("world/characters/dog/front_v4.png");
	}

	@Test
	@DisplayName("없는 캐릭터면 자리를 안 준다")
	void rejectsUnknownCharacter() {
		given(worldCharacterRepository.existsById("pluto")).willReturn(false);

		assertThatThrownBy(() -> adminWorldAssetService.createUploadUrl(
			"pluto", new AdminWorldAssetUploadRequest("front", "image/png")))
			.isInstanceOf(WorldException.class);
		then(s3Service).should(never()).generatePresignedUrlForKey(any(), any());
	}

	@Test
	@DisplayName("남의 캐릭터 자리 키는 등록할 수 없다")
	void rejectsForeignKey() {
		given(worldCharacterRepository.existsById("dog")).willReturn(true);

		assertThatThrownBy(() -> adminWorldAssetService.register("dog",
			new AdminWorldAssetRegisterRequest("front", "world/characters/cat/front_v1.png", 360, 354)))
			.isInstanceOf(WorldException.class)
			.hasMessageContaining("그 캐릭터의 그림 키가 아닙니다");
		then(worldCatalogVersionRaiser).should(never()).raise();
	}

	@Test
	@DisplayName("등록하면 카탈로그 번호가 오른다")
	void registerRaisesCatalogVersion() {
		given(worldCharacterRepository.existsById("dog")).willReturn(true);
		given(worldCharacterAssetRepository.findById(any())).willReturn(Optional.empty());

		adminWorldAssetService.register("dog",
			new AdminWorldAssetRegisterRequest("front", "world/characters/dog/front_v1.png", 360, 354));

		ArgumentCaptor<WorldCharacterAsset> saved = ArgumentCaptor.forClass(WorldCharacterAsset.class);
		then(worldCharacterAssetRepository).should().save(saved.capture());
		assertThat(saved.getValue().getImageKey()).isEqualTo("world/characters/dog/front_v1.png");
		then(worldCatalogVersionRaiser).should().raise();
	}

	@Test
	@DisplayName("등록 해제는 카탈로그에서만 뺀다 — S3 객체는 남긴다")
	void unregisterKeepsTheObject() {
		given(worldCharacterAssetRepository.findById(any())).willReturn(Optional.of(
			WorldFixtures.asset("dog", "front", "world/characters/dog/front_v1.png")));

		adminWorldAssetService.unregister("dog", "front");

		then(worldCharacterAssetRepository).should().delete(any());
		then(s3Service).should(never()).deleteObject(eq("world/characters/dog/front_v1.png"));
		then(worldCatalogVersionRaiser).should().raise();
	}
}
