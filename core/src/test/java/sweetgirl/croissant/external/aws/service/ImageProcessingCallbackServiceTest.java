package sweetgirl.croissant.external.aws.service;

import static org.assertj.core.api.Assertions.assertThat;
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

import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.external.aws.entity.ImageProcessingCallback;
import sweetgirl.croissant.external.aws.repository.ImageProcessingCallbackRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreImageFile;

@ExtendWith(MockitoExtension.class)
class ImageProcessingCallbackServiceTest {

	@Mock
	ImageProcessingCallbackRepository imageProcessingCallbackRepository;

	@InjectMocks
	ImageProcessingCallbackService imageProcessingCallbackService;

	@Test
	@DisplayName("savePendingCallback: 동일 awsKey 콜백을 원자적으로 upsert한다")
	void savePendingCallback_upsertsAtomically() {
		imageProcessingCallbackService.savePendingCallback("bread-records/members/7/images/a", ImageStatus.DONE, null);

		verify(imageProcessingCallbackRepository)
			.upsert("bread-records/members/7/images/a", "DONE", null);
	}

	@Test
	@DisplayName("applyStatus: DONE이면 상태와 resized key 3개를 저장한다")
	void applyStatus_done_savesResizedKeys() {
		String awsKey = "stores/1/image.jpg";
		StoreImageFile image = image(awsKey);

		imageProcessingCallbackService.applyStatus(image, ImageStatus.DONE, null);

		assertThat(image.getProcessingStatus()).isEqualTo(ImageStatus.DONE);
		assertThat(image.getResizedKey320()).isEqualTo("resized/320/" + awsKey);
		assertThat(image.getResizedKey640()).isEqualTo("resized/640/" + awsKey);
		assertThat(image.getResizedKey1024()).isEqualTo("resized/1024/" + awsKey);
	}

	@Test
	@DisplayName("applyStatus: FAILED이면 상태를 실패로 바꾸고 resized key를 비운다")
	void applyStatus_failed_clearsResizedKeys() {
		String awsKey = "stores/1/image.jpg";
		StoreImageFile image = image(awsKey);
		image.updateResizedKeys(awsKey);

		imageProcessingCallbackService.applyStatus(image, ImageStatus.FAILED, "invalid");

		assertThat(image.getProcessingStatus()).isEqualTo(ImageStatus.FAILED);
		assertThat(image.getResizedKey320()).isNull();
		assertThat(image.getResizedKey640()).isNull();
		assertThat(image.getResizedKey1024()).isNull();
	}

	@Test
	@DisplayName("applyPendingCallback: 보류 DONE 콜백을 이미지에 적용하고 삭제한다")
	void applyPendingCallback_done_appliesAndDeletesCallback() {
		String awsKey = "stores/1/image.jpg";
		StoreImageFile image = image(awsKey);
		ImageProcessingCallback callback = new ImageProcessingCallback(awsKey, ImageStatus.DONE, null);
		given(imageProcessingCallbackRepository.findByAwsKey(awsKey)).willReturn(Optional.of(callback));

		imageProcessingCallbackService.applyPendingCallback(image);

		assertThat(image.getProcessingStatus()).isEqualTo(ImageStatus.DONE);
		assertThat(image.getResizedKey640()).isEqualTo("resized/640/" + awsKey);
		verify(imageProcessingCallbackRepository).delete(callback);
	}

	@Test
	@DisplayName("deletePendingCallback: awsKey의 보류 콜백을 지운다")
	void deletePendingCallback_deletesByAwsKey() {
		imageProcessingCallbackService.deletePendingCallback("bread-records/members/7/images/a");

		verify(imageProcessingCallbackRepository).deleteByAwsKey("bread-records/members/7/images/a");
	}

	private StoreImageFile image(String awsKey) {
		return StoreImageFile.builder()
			.store(mock(Store.class))
			.name("image.jpg")
			.mimeType(MimeType.IMAGE_JPEG)
			.awsKey(awsKey)
			.displayOrder(1)
			.build();
	}
}
