package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAdminRequests.RemoveAsset;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAdminRequests.ReplaceAsset;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAssetUploadResponse;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset.Kind;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportAssetRepository;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetCommandService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetQueryService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetStore;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetUploadService;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportAssetReplacementRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.repository.WorldPlanetRepository;

class MonthlyReportAssetTest {
	private final MonthlyReportAssetRepository repository = mock(MonthlyReportAssetRepository.class);
	private final WorldCharacterRepository characters = mock(WorldCharacterRepository.class);
	private final WorldPlanetRepository planets = mock(WorldPlanetRepository.class);
	private final MonthlyReportAssetStore store = mock(MonthlyReportAssetStore.class);
	private final MonthlyReportAssetQueryService queries = new MonthlyReportAssetQueryService(repository, characters,
		planets);
	private final MonthlyReportAssetReplacementRepository replacements = mock(
		MonthlyReportAssetReplacementRepository.class);
	private final MonthlyReportAssetCommandService commands = new MonthlyReportAssetCommandService(repository, queries,
		replacements);
	private final MonthlyReportAssetUploadService service = new MonthlyReportAssetUploadService(queries,
		commands, store);

	@Test
	void uploadAllocatesUniqueKeysWithinVersionAndCoverMonth() {
		when(characters.existsById("cat")).thenReturn(true);
		Asset request = new Asset("v1", Kind.COVER, "cat", "", "09", "image/png", null);
		service.issueAssetUploadUrl(request);
		service.issueAssetUploadUrl(request);
		ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
		verify(store, times(2)).issueAssetUploadUrl(keys.capture(), eq("image/png"));
		assertThat(keys.getAllValues()).doesNotHaveDuplicates().allSatisfy(key ->
			assertThat(key).matches("monthly-reports/assets/v1/covers/cat/09-[a-f0-9-]+\\.png"));
	}

	@Test
	void acceptsExistingFaceWithoutReuploadAndValidatesItsObject() {
		when(characters.existsById("cat")).thenReturn(true);
		String key = "monthly-reports/assets/v1/faces/cat/smile.png";
		String id = MonthlyReportAsset.identity("v1", Kind.FACE, "cat", "", "smile");
		MonthlyReportAsset asset = mock(MonthlyReportAsset.class);
		when(asset.getImageKey()).thenReturn(key);
		when(repository.findById(id)).thenReturn(Optional.of(asset));
		assertThat(service.registerReportAsset(new Asset("v1", Kind.FACE, "cat", "", "smile", "image/png", key)))
			.extracting(value -> value.imageKey()).isEqualTo(key);
		verify(store).validateStoredAsset(key, "image/png");
		service.issueAssetUploadUrl(new Asset("v1", Kind.FACE, "cat", "", "wink", "image/webp", null));
		ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
		verify(store).issueAssetUploadUrl(keys.capture(), eq("image/webp"));
		assertThat(keys.getValue()).matches("monthly-reports/assets/v1/faces/cat/wink-[a-f0-9-]+\\.webp");
	}

	@Test
	void rejectsInvalidFaceExpressionPlanetAndDifferentSlot() {
		when(characters.existsById("cat")).thenReturn(true);
		for (Asset request : new Asset[] {
			new Asset("v1", Kind.FACE, "cat", "", "record", "image/png", null),
			new Asset("v1", Kind.FACE, "cat", "earth", "smile", "image/png", null),
			new Asset("v1", Kind.FACE, "cat", "", "smile", "image/png",
				"monthly-reports/assets/v1/faces/dog/smile.png")}) {
			assertThatThrownBy(() -> service.registerReportAsset(request)).isInstanceOf(CustomException.class);
		}
		verifyNoInteractions(store, repository);
	}

	@Test
	void rejectsUnknownTypeAndKeyOutsideItsRegisteredSlot() {
		when(characters.existsById("cat")).thenReturn(true);
		when(planets.existsById("moon")).thenReturn(true);
		assertThatThrownBy(() -> service.registerReportAsset(new Asset("v1", Kind.TYPE, "cat", "moon", "unknown",
				"image/png", "bad")))
			.isInstanceOf(CustomException.class);
		assertThatThrownBy(() -> service.registerReportAsset(new Asset("v1", Kind.COVER, "cat", "", "09", "image/png",
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
		assertThat(service.registerReportAsset(new Asset("v1", Kind.COVER, "cat", "", "09", "image/png", key)))
			.extracting(value -> value.imageKey()).isEqualTo(key);
		assertThatThrownBy(() -> service.registerReportAsset(new Asset("v1", Kind.COVER, "cat", "", "09", "image/png",
			"monthly-reports/assets/v1/covers/cat/09-new.png"))).isInstanceOf(CustomException.class);
		verify(store).validateStoredAsset(key, "image/png");
	}

	@Test
	void presignedUploadSignsConditionalWriteAndContentTypeWithoutNetwork() {
		try (S3Presigner presigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
			.build()) {
			S3Service s3 = new S3Service(presigner, mock(S3Client.class));
			ReflectionTestUtils.setField(s3, "bucketName", "test-assets");
			MonthlyReportAssetStore storage = new MonthlyReportAssetStore(s3);
			MonthlyReportAssetUploadResponse upload = storage.issueAssetUploadUrl("monthly-reports/assets/v1/09.png",
				"image/png");
			assertThat(upload.headers()).containsEntry("If-None-Match", "*").containsEntry("Content-Type", "image/png");
			assertThat(upload.uploadUrl()).contains("X-Amz-Expires=600", "if-none-match", "content-type");
		}
	}

	@Test
	void replacesSameArtworkAndRejectsStaleChanges() {
		when(characters.existsById("cat")).thenReturn(true);
		String oldKey = "monthly-reports/assets/v1/faces/cat/smile.png";
		String newKey = "monthly-reports/assets/v1/faces/cat/smile-new.webp";
		MonthlyReportAsset asset = mock(MonthlyReportAsset.class);
		when(asset.getImageKey()).thenReturn(oldKey);
		String id = MonthlyReportAsset.identity("v1", Kind.FACE, "cat", "", "smile");
		when(repository.findForUpdate(id)).thenReturn(Optional.of(asset));
		Asset replacement = new Asset("v1", Kind.FACE, "cat", "", "smile", "image/webp", newKey);
		service.replaceReportAsset(new ReplaceAsset(replacement, oldKey, true));
		verify(store).validateStoredAsset(newKey, "image/webp");
		verify(replacements).forwardReplacements(oldKey, newKey);
		verify(asset).replaceImageKey(newKey);
		assertThatThrownBy(() -> service.replaceReportAsset(new ReplaceAsset(replacement, "stale.png", true)))
			.isInstanceOf(CustomException.class);
		verify(asset, times(1)).replaceImageKey(newKey);
	}

	@Test
	void futureOnlyReplacementDoesNotChangePublishedArtwork() {
		when(characters.existsById("cat")).thenReturn(true);
		MonthlyReportAsset asset = mock(MonthlyReportAsset.class);
		when(asset.getImageKey()).thenReturn("old.png");
		when(repository.findForUpdate("v1/FACE/cat//smile")).thenReturn(Optional.of(asset));
		Asset replacement = new Asset("v1", Kind.FACE, "cat", "", "smile", "image/png",
			"monthly-reports/assets/v1/faces/cat/smile-new.png");
		service.replaceReportAsset(new ReplaceAsset(replacement, "old.png", false));
		verify(replacements, times(0)).save(any());
	}

	@Test
	void rejectsPngForExistingReportsBeforeCallingS3() {
		Asset replacement = new Asset("v1", Kind.FACE, "cat", "", "smile", "image/png", "new.png");
		assertThatThrownBy(() -> service.replaceReportAsset(new ReplaceAsset(replacement, "old.png", true)))
			.isInstanceOf(CustomException.class);
		verifyNoInteractions(store, repository, replacements);
	}

	@Test
	void deleteRejectsStaleKeyAndPreservesS3AndReplacements() {
		MonthlyReportAsset asset = mock(MonthlyReportAsset.class);
		when(asset.getImageKey()).thenReturn("current.webp");
		when(repository.findForUpdate("v1/FACE/cat//smile")).thenReturn(Optional.of(asset));
		assertThatThrownBy(() -> commands.removeReportAsset(new RemoveAsset("v1/FACE/cat//smile", "old.png")))
			.isInstanceOf(CustomException.class);
		verify(repository, times(0)).delete(asset);
		commands.removeReportAsset(new RemoveAsset("v1/FACE/cat//smile", "current.webp"));
		verify(repository).delete(asset);
		commands.removeReportAsset(new RemoveAsset("missing", "old.png"));
		verifyNoInteractions(store, replacements);
	}
}
