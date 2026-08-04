package sweetgirl.croissant.external.aws.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.contribution.repository.MenuContributionReportImageFileRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportImageFileRepository;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.external.aws.dto.ImageStatusUpdateRequest;
import sweetgirl.croissant.external.aws.exception.S3StorageException;
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

	@InjectMocks
	ImageCallbackService imageCallbackService;

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

		verify(imageProcessingCallbackService).applyStatus(image, ImageStatus.DONE, null);
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

		verify(imageProcessingCallbackService).applyStatus(image, ImageStatus.FAILED, null);
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
		given(breadRecordPhotoRepository.findByAwsKey(awsKey)).willReturn(Optional.of(photo));

		imageCallbackService.updateImageStatus(request);

		verify(imageProcessingCallbackService).applyStatus(photo, ImageStatus.DONE, null);
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
