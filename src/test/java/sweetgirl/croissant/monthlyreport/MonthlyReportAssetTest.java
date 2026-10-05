package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAdminRequests.Asset;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset.Kind;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportAssetRepository;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetStore;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.repository.WorldPlanetRepository;

class MonthlyReportAssetTest {
	private final MonthlyReportAssetRepository repository = mock(MonthlyReportAssetRepository.class);
	private final WorldCharacterRepository characters = mock(WorldCharacterRepository.class);
	private final WorldPlanetRepository planets = mock(WorldPlanetRepository.class);
	private final MonthlyReportAssetStore store = mock(MonthlyReportAssetStore.class);
	private final MonthlyReportAssetService service = new MonthlyReportAssetService(repository, characters, planets,
		store);

	@Test
	void uploadAllocatesUniqueKeysWithinVersionAndCoverMonth() {
		when(characters.existsById("cat")).thenReturn(true);
		Asset request = new Asset("v1", Kind.COVER, "cat", "", "09", "image/png", null);
		service.upload(request);
		service.upload(request);
		ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
		verify(store, times(2)).upload(keys.capture(), eq("image/png"));
		assertThat(keys.getAllValues()).doesNotHaveDuplicates().allSatisfy(key ->
			assertThat(key).matches("monthly-reports/assets/v1/covers/cat/09-[a-f0-9-]+\\.png"));
	}

	@Test
	void rejectsUnknownTypeAndKeyOutsideItsRegisteredSlot() {
		when(characters.existsById("cat")).thenReturn(true);
		when(planets.existsById("moon")).thenReturn(true);
		assertThatThrownBy(() -> service.register(new Asset("v1", Kind.TYPE, "cat", "moon", "unknown",
				"image/png", "bad")))
			.isInstanceOf(CustomException.class);
		assertThatThrownBy(() -> service.register(new Asset("v1", Kind.COVER, "cat", "", "09", "image/png",
			"monthly-reports/assets/v1/covers/cat/../09.png"))).isInstanceOf(CustomException.class);
		verifyNoInteractions(store, repository);
	}

	@Test
	void registeredSlotIsIdempotentAndCannotBeRepointed() {
		when(characters.existsById("cat")).thenReturn(true);
		String key = "monthly-reports/assets/v1/covers/cat/09.png";
		String id = MonthlyReportAsset.identity("v1", Kind.COVER, "cat", "", "09");
		MonthlyReportAsset asset = mock(MonthlyReportAsset.class);
		when(asset.getImageKey()).thenReturn(key);
		when(repository.findById(id)).thenReturn(Optional.of(asset));
		assertThat(service.register(new Asset("v1", Kind.COVER, "cat", "", "09", "image/png", key))).isSameAs(asset);
		assertThatThrownBy(() -> service.register(new Asset("v1", Kind.COVER, "cat", "", "09", "image/png",
			"monthly-reports/assets/v1/covers/cat/09-new.png"))).isInstanceOf(CustomException.class);
		verify(store).verify(key, "image/png");
	}

	@Test
	void presignedUploadSignsConditionalWriteAndContentTypeWithoutNetwork() {
		try (S3Presigner presigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
			.build()) {
			S3Service s3 = new S3Service(presigner, mock(S3Client.class));
			ReflectionTestUtils.setField(s3, "bucketName", "test-assets");
			MonthlyReportAssetStore storage = new MonthlyReportAssetStore(s3);
			MonthlyReportAssetStore.Upload upload = storage.upload("monthly-reports/assets/v1/09.png", "image/png");
			assertThat(upload.headers()).containsEntry("If-None-Match", "*").containsEntry("Content-Type", "image/png");
			assertThat(upload.uploadUrl()).contains("X-Amz-Expires=600", "if-none-match", "content-type");
		}
	}
}
