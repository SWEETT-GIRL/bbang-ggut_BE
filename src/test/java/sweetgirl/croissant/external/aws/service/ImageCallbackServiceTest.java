package sweetgirl.croissant.external.aws.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.contribution.repository.MenuContributionReportImageFileRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportImageFileRepository;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.external.aws.dto.ImageStatusUpdateRequest;
import sweetgirl.croissant.external.aws.exception.S3StorageException;
import sweetgirl.croissant.external.aws.metrics.ImagePipelineMetrics;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.review.repository.ReviewImageFileRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

@ExtendWith(MockitoExtension.class)
class ImageCallbackServiceTest {

	@Mock
	StoreImageFileRepository storeImageFileRepository;
	@Mock
	MenuImageFileRepository menuImageFileRepository;
	@Mock
	StoreContributionReportImageFileRepository storeContributionReportImageFileRepository;
	@Mock
	MenuContributionReportImageFileRepository menuContributionReportImageFileRepository;
	@Mock
	ReviewImageFileRepository reviewImageFileRepository;
	@Mock
	CourseImageFileRepository courseImageFileRepository;
	@Mock
	BreadRecordPhotoRepository breadRecordPhotoRepository;
	@Mock
	ImageProcessingCallbackService imageProcessingCallbackService;

	// 지표는 순수 계산이라 목킹하지 않고 실제 구현에 테스트용 레지스트리를 물린다.
	final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
	@Spy
	ImagePipelineMetrics imagePipelineMetrics = new ImagePipelineMetrics(meterRegistry);

	@InjectMocks
	ImageCallbackService imageCallbackService;

	private double callbackCount(ImageStatus status, String mimeType) {
		Counter counter = meterRegistry.find("image.callback")
			.tag("status", status.name().toLowerCase(Locale.ROOT))
			.tag("mime_type", mimeType)
			.counter();
		return counter == null ? -1 : counter.count();
	}

	private double orphanCount() {
		Counter counter = meterRegistry.find("image.orphan.callback").counter();
		return counter == null ? -1 : counter.count();
	}

	@Test
	@DisplayName("DONE 수신 시 리사이즈 키 3개를 resized/{width}/{awsKey} 패턴으로 저장한다")
	void updateImageStatus_done_savesResizedKeys() {
		String awsKey = "stores/1/image.jpg";
		StoreImageFile image = StoreImageFile.builder()
			.store(mock(Store.class))
			.name("image.jpg")
			.mimeType(MimeType.IMAGE_JPEG)
			.awsKey(awsKey)
			.displayOrder(1)
			.build();

		ImageStatusUpdateRequest request = mock(ImageStatusUpdateRequest.class);
		given(request.getAwsKey()).willReturn(awsKey);
		given(request.getStatus()).willReturn(ImageStatus.DONE);
		given(storeImageFileRepository.findByAwsKey(awsKey)).willReturn(Optional.of(image));

		imageCallbackService.updateImageStatus(request);

		verify(imageProcessingCallbackService).savePendingCallback(awsKey, ImageStatus.DONE, null);
		verify(imageProcessingCallbackService).applyPendingCallback(image);
	}

	@Test
	@DisplayName("FAILED 수신 시 리사이즈 키는 null로 유지된다")
	void updateImageStatus_failed_resizedKeysRemainNull() {
		String awsKey = "stores/1/image.jpg";
		StoreImageFile image = StoreImageFile.builder()
			.store(mock(Store.class))
			.name("image.jpg")
			.mimeType(MimeType.IMAGE_JPEG)
			.awsKey(awsKey)
			.displayOrder(1)
			.build();

		ImageStatusUpdateRequest request = mock(ImageStatusUpdateRequest.class);
		given(request.getAwsKey()).willReturn(awsKey);
		given(request.getStatus()).willReturn(ImageStatus.FAILED);
		given(storeImageFileRepository.findByAwsKey(awsKey)).willReturn(Optional.of(image));

		imageCallbackService.updateImageStatus(request);

		verify(imageProcessingCallbackService).savePendingCallback(awsKey, ImageStatus.FAILED, null);
		verify(imageProcessingCallbackService).applyPendingCallback(image);
	}

	@Test
	@DisplayName("지원 prefix의 row가 아직 없으면 콜백을 보류 저장한다")
	void updateImageStatus_knownPrefixWithoutImage_savesPendingCallback() {
		String awsKey = "stores/1/image.jpg";

		ImageStatusUpdateRequest request = mock(ImageStatusUpdateRequest.class);
		given(request.getAwsKey()).willReturn(awsKey);
		given(request.getStatus()).willReturn(ImageStatus.DONE);
		given(storeImageFileRepository.findByAwsKey(awsKey)).willReturn(Optional.empty());

		imageCallbackService.updateImageStatus(request);

		verify(imageProcessingCallbackService).savePendingCallback(awsKey, ImageStatus.DONE, null);
	}

	@Test
	@DisplayName("빵 기록 이미지 prefix 콜백을 빵 기록 사진에 반영한다")
	void updateImageStatus_breadRecordImage_updatesPhoto() {
		String awsKey = "bread-records/members/7/images/a";
		BreadRecordPhoto photo = mock(BreadRecordPhoto.class);
		ImageStatusUpdateRequest request = mock(ImageStatusUpdateRequest.class);
		given(request.getAwsKey()).willReturn(awsKey);
		given(request.getStatus()).willReturn(ImageStatus.DONE);
		given(breadRecordPhotoRepository.findAllByAwsKey(awsKey)).willReturn(List.of(photo));

		imageCallbackService.updateImageStatus(request);

		InOrder callbackOrder = inOrder(imageProcessingCallbackService, breadRecordPhotoRepository);
		callbackOrder.verify(imageProcessingCallbackService)
			.savePendingCallback(awsKey, ImageStatus.DONE, null);
		callbackOrder.verify(breadRecordPhotoRepository).findAllByAwsKey(awsKey);
		callbackOrder.verify(imageProcessingCallbackService).applySameKeyPendingCallback(List.of(photo));
	}

	@Test
	@DisplayName("같은 awsKey의 빵 기록 사진이 여러 장이어도 예외 없이 모두 반영한다")
	void updateImageStatus_duplicateBreadRecordPhotos_appliesToAll() {
		String awsKey = "bread-records/members/7/images/dup";
		BreadRecordPhoto first = mock(BreadRecordPhoto.class);
		BreadRecordPhoto second = mock(BreadRecordPhoto.class);
		ImageStatusUpdateRequest request = mock(ImageStatusUpdateRequest.class);
		given(request.getAwsKey()).willReturn(awsKey);
		given(request.getStatus()).willReturn(ImageStatus.DONE);
		given(breadRecordPhotoRepository.findAllByAwsKey(awsKey)).willReturn(List.of(first, second));

		imageCallbackService.updateImageStatus(request);

		verify(imageProcessingCallbackService).applySameKeyPendingCallback(List.of(first, second));
	}

	@Test
	@DisplayName("지표 — 반영에 성공하면 status·mime_type 태그로 집계한다")
	void updateImageStatus_recordsCallbackMetricWithMimeType() {
		String awsKey = "stores/1/image.jpg";
		StoreImageFile image = StoreImageFile.builder()
			.store(mock(Store.class))
			.name("image.jpg")
			.mimeType(MimeType.IMAGE_JPEG)
			.awsKey(awsKey)
			.displayOrder(1)
			.build();
		ImageStatusUpdateRequest request = mock(ImageStatusUpdateRequest.class);
		given(request.getAwsKey()).willReturn(awsKey);
		given(request.getStatus()).willReturn(ImageStatus.DONE);
		given(storeImageFileRepository.findByAwsKey(awsKey)).willReturn(Optional.of(image));

		imageCallbackService.updateImageStatus(request);

		assertThat(callbackCount(ImageStatus.DONE, "image_jpeg")).isEqualTo(1.0);
		assertThat(orphanCount()).isEqualTo(-1.0);
	}

	@Test
	@DisplayName("지표 — GIF 실패는 mime_type으로 구분돼 진짜 실패와 섞이지 않는다")
	void updateImageStatus_separatesGifFailureByMimeType() {
		String awsKey = "stores/1/animation.gif";
		StoreImageFile image = StoreImageFile.builder()
			.store(mock(Store.class))
			.name("animation.gif")
			.mimeType(MimeType.IMAGE_GIF)
			.awsKey(awsKey)
			.displayOrder(1)
			.build();
		ImageStatusUpdateRequest request = mock(ImageStatusUpdateRequest.class);
		given(request.getAwsKey()).willReturn(awsKey);
		given(request.getStatus()).willReturn(ImageStatus.FAILED);
		given(storeImageFileRepository.findByAwsKey(awsKey)).willReturn(Optional.of(image));

		imageCallbackService.updateImageStatus(request);

		// GIF는 리사이즈 대상이 아니라 의도적으로 실패한다. 태그가 없으면 장애와 구분할 수 없다.
		assertThat(callbackCount(ImageStatus.FAILED, "image_gif")).isEqualTo(1.0);
		assertThat(callbackCount(ImageStatus.FAILED, "image_jpeg")).isEqualTo(-1.0);
	}

	@Test
	@DisplayName("지표 — 행보다 먼저 온 콜백은 orphan으로 집계하고 mime은 unknown이 된다")
	void updateImageStatus_recordsOrphanWhenImageRowMissing() {
		String awsKey = "stores/1/image.jpg";
		ImageStatusUpdateRequest request = mock(ImageStatusUpdateRequest.class);
		given(request.getAwsKey()).willReturn(awsKey);
		given(request.getStatus()).willReturn(ImageStatus.DONE);
		given(storeImageFileRepository.findByAwsKey(awsKey)).willReturn(Optional.empty());

		imageCallbackService.updateImageStatus(request);

		assertThat(orphanCount()).isEqualTo(1.0);
		// 반영할 행이 없어 MIME을 알 수 없다
		assertThat(callbackCount(ImageStatus.DONE, "unknown")).isEqualTo(1.0);
	}

	@Test
	@DisplayName("알 수 없는 prefix의 awsKey는 예외가 발생한다")
	void updateImageStatus_unknownPrefix_throwsException() {
		String awsKey = "unknown/prefix/image.jpg";

		ImageStatusUpdateRequest request = mock(ImageStatusUpdateRequest.class);
		given(request.getAwsKey()).willReturn(awsKey);
		given(request.getStatus()).willReturn(ImageStatus.DONE);

		assertThatThrownBy(() -> imageCallbackService.updateImageStatus(request))
			.isInstanceOf(S3StorageException.class);
	}
}
