package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.ImageUploadFromUrlRequest;
import sweetgirl.croissant.external.aws.dto.ImageUploadFromUrlResponse;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.external.aws.exception.S3ErrorCode;
import sweetgirl.croissant.external.aws.exception.S3StorageException;
import sweetgirl.croissant.external.aws.util.ImageDownloader;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class AdminImageServiceTest {

	@Mock
	S3Service s3Service;
	@Mock
	StoreRepository storeRepository;
	@Mock
	MenuRepository menuRepository;
	@Mock
	StoreImageFileRepository storeImageFileRepository;
	@Mock
	MenuImageFileRepository menuImageFileRepository;
	@Mock
	ImageDownloader imageDownloader;
	@Mock
	ImageRowSaver imageRowSaver;

	@InjectMocks
	AdminImageService adminImageService;

	private ImageUploadFromUrlRequest req(String url) {
		ImageUploadFromUrlRequest request = new ImageUploadFromUrlRequest();
		try {
			java.lang.reflect.Field urlField = ImageUploadFromUrlRequest.class.getDeclaredField("url");
			urlField.setAccessible(true);
			urlField.set(request, url);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
		return request;
	}

	@Test
	@DisplayName("uploadImagesFromUrls: 모든 URL 성공 시 SUCCESS 카운트가 일치한다")
	void uploadImagesFromUrls_allSuccess() {
		given(storeRepository.existsById(1L)).willReturn(true);
		given(storeImageFileRepository.findMaxDisplayOrderByStoreId(1L)).willReturn(0);
		given(imageDownloader.download(anyString())).willReturn(
			ImageDownloader.Result.success(new byte[] {1, 2, 3}, MimeType.IMAGE_JPEG));
		given(imageRowSaver.saveStoreImage(eq(1L), anyString(), anyString(), any(), anyInt(), anyString()))
			.willReturn(101L, 102L);

		ImageUploadFromUrlResponse response = adminImageService.uploadImagesFromUrls(
			"stores", 1L, List.of(req("https://a.com/1.jpg"), req("https://a.com/2.jpg")));

		assertThat(response.successCount()).isEqualTo(2);
		assertThat(response.failureCount()).isZero();
		assertThat(response.results()).extracting(ImageUploadFromUrlResponse.Item::status)
			.containsOnly(ImageUploadFromUrlResponse.Status.SUCCESS);
	}

	@Test
	@DisplayName("uploadImagesFromUrls: 일부 URL 다운로드 실패해도 나머지는 저장된다")
	void uploadImagesFromUrls_partialDownloadFail() {
		given(storeRepository.existsById(1L)).willReturn(true);
		given(storeImageFileRepository.findMaxDisplayOrderByStoreId(1L)).willReturn(0);
		given(imageDownloader.download("https://a.com/ok.jpg")).willReturn(
			ImageDownloader.Result.success(new byte[] {1}, MimeType.IMAGE_JPEG));
		given(imageDownloader.download("https://a.com/bad.jpg")).willReturn(
			ImageDownloader.Result.failure("status 403"));
		given(imageRowSaver.saveStoreImage(eq(1L), anyString(), anyString(), any(), anyInt(), anyString()))
			.willReturn(101L);

		ImageUploadFromUrlResponse response = adminImageService.uploadImagesFromUrls(
			"stores", 1L, List.of(req("https://a.com/ok.jpg"), req("https://a.com/bad.jpg")));

		assertThat(response.successCount()).isEqualTo(1);
		assertThat(response.failureCount()).isEqualTo(1);
		assertThat(response.results().get(0).status()).isEqualTo(ImageUploadFromUrlResponse.Status.SUCCESS);
		assertThat(response.results().get(1).status()).isEqualTo(ImageUploadFromUrlResponse.Status.DOWNLOAD_FAILED);
		assertThat(response.results().get(1).reason()).contains("403");
	}

	@Test
	@DisplayName("uploadImagesFromUrls: DB 저장 실패 시 S3 객체를 삭제하여 orphan 을 막는다")
	void uploadImagesFromUrls_dbSaveFail_cleansS3() {
		given(storeRepository.existsById(1L)).willReturn(true);
		given(storeImageFileRepository.findMaxDisplayOrderByStoreId(1L)).willReturn(0);
		given(imageDownloader.download(anyString())).willReturn(
			ImageDownloader.Result.success(new byte[] {1}, MimeType.IMAGE_JPEG));
		willThrow(new RuntimeException("constraint violation"))
			.given(imageRowSaver).saveStoreImage(eq(1L), anyString(), anyString(), any(), anyInt(), anyString());

		ImageUploadFromUrlResponse response = adminImageService.uploadImagesFromUrls(
			"stores", 1L, List.of(req("https://a.com/x.jpg")));

		assertThat(response.failureCount()).isEqualTo(1);
		assertThat(response.results().get(0).status()).isEqualTo(ImageUploadFromUrlResponse.Status.DB_FAILED);
		verify(s3Service).deleteObject(anyString());
	}

	@Test
	@DisplayName("uploadImagesFromUrls: S3 PUT 실패 시 DB 저장은 호출되지 않는다")
	void uploadImagesFromUrls_s3Fail() {
		given(storeRepository.existsById(1L)).willReturn(true);
		given(storeImageFileRepository.findMaxDisplayOrderByStoreId(1L)).willReturn(0);
		given(imageDownloader.download(anyString())).willReturn(
			ImageDownloader.Result.success(new byte[] {1}, MimeType.IMAGE_JPEG));
		willThrow(new S3StorageException(S3ErrorCode.UPLOAD_FAILED, "s3 5xx"))
			.given(s3Service).putObject(anyString(), any(), any());

		ImageUploadFromUrlResponse response = adminImageService.uploadImagesFromUrls(
			"stores", 1L, List.of(req("https://a.com/x.jpg")));

		assertThat(response.failureCount()).isEqualTo(1);
		assertThat(response.results().get(0).status()).isEqualTo(ImageUploadFromUrlResponse.Status.UPLOAD_FAILED);
		verify(imageRowSaver, never()).saveStoreImage(anyLong(), anyString(), anyString(), any(), anyInt(),
			anyString());
	}

	@Test
	@DisplayName("uploadImagesFromUrls: store not found 면 예외를 던진다")
	void uploadImagesFromUrls_storeNotFound() {
		given(storeRepository.existsById(999L)).willReturn(false);

		assertThatThrownBy(() -> adminImageService.uploadImagesFromUrls(
			"stores", 999L, List.of(req("https://a.com/1.jpg"))))
			.isInstanceOf(StoreException.class);
	}

	@Test
	@DisplayName("uploadImagesFromUrls: 잘못된 domain 은 IllegalArgumentException")
	void uploadImagesFromUrls_invalidDomain() {
		assertThatThrownBy(() -> adminImageService.uploadImagesFromUrls(
			"banners", 1L, List.of(req("https://a.com/1.jpg"))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("uploadImagesFromUrls: 빈 URL 은 INVALID_URL 로 표시된다")
	void uploadImagesFromUrls_blankUrl() {
		given(menuRepository.existsById(1L)).willReturn(true);
		given(menuImageFileRepository.findMaxDisplayOrderByMenuId(1L)).willReturn(0);

		ImageUploadFromUrlResponse response = adminImageService.uploadImagesFromUrls(
			"menus", 1L, List.of(req("")));

		assertThat(response.results().get(0).status()).isEqualTo(ImageUploadFromUrlResponse.Status.INVALID_URL);
	}

	@Test
	@DisplayName("uploadImagesFromUrls: displayOrder 가 null 이면 기존 최대값+1 부터 부여한다")
	void uploadImagesFromUrls_autoDisplayOrder() {
		given(storeRepository.existsById(1L)).willReturn(true);
		given(storeImageFileRepository.findMaxDisplayOrderByStoreId(1L)).willReturn(5);
		given(imageDownloader.download(anyString())).willReturn(
			ImageDownloader.Result.success(new byte[] {1}, MimeType.IMAGE_JPEG));
		given(imageRowSaver.saveStoreImage(eq(1L), anyString(), anyString(), any(), anyInt(), anyString()))
			.willReturn(1L, 2L);

		adminImageService.uploadImagesFromUrls(
			"stores", 1L, List.of(req("https://a.com/1.jpg"), req("https://a.com/2.jpg")));

		verify(imageRowSaver).saveStoreImage(eq(1L), anyString(), anyString(), any(), eq(6), anyString());
		verify(imageRowSaver).saveStoreImage(eq(1L), anyString(), anyString(), any(), eq(7), anyString());
	}

	@Test
	@DisplayName("uploadImagesFromUrls: 같은 (target, source_url) 이미 있으면 SKIPPED — 다운로드/S3/DB 호출 없음")
	void uploadImagesFromUrls_duplicateSourceUrl_skipped() {
		given(storeRepository.existsById(1L)).willReturn(true);
		given(storeImageFileRepository.findMaxDisplayOrderByStoreId(1L)).willReturn(0);

		sweetgirl.croissant.store.entity.StoreImageFile existing = org.mockito.Mockito
			.mock(sweetgirl.croissant.store.entity.StoreImageFile.class);
		given(existing.getId()).willReturn(77L);
		given(existing.getAwsKey()).willReturn("stores/1/abc");
		given(storeImageFileRepository.findByStoreIdAndSourceUrl(1L, "https://a.com/dup.jpg"))
			.willReturn(java.util.Optional.of(existing));

		ImageUploadFromUrlResponse response = adminImageService.uploadImagesFromUrls(
			"stores", 1L, List.of(req("https://a.com/dup.jpg")));

		assertThat(response.successCount()).isEqualTo(1);
		assertThat(response.failureCount()).isZero();
		assertThat(response.results().get(0).status()).isEqualTo(ImageUploadFromUrlResponse.Status.SKIPPED);
		assertThat(response.results().get(0).imageId()).isEqualTo(77L);
		assertThat(response.results().get(0).awsKey()).isEqualTo("stores/1/abc");

		org.mockito.Mockito.verifyNoInteractions(imageDownloader);
		verify(s3Service, never()).putObject(anyString(), any(), any());
		verify(imageRowSaver, never()).saveStoreImage(anyLong(), anyString(), anyString(), any(), anyInt(),
			anyString());
	}
}
