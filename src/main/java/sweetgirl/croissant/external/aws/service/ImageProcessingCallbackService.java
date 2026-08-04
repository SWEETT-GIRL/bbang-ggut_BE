package sweetgirl.croissant.external.aws.service;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.common.entity.ImageFile;
import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.external.aws.entity.ImageProcessingCallback;
import sweetgirl.croissant.external.aws.repository.ImageProcessingCallbackRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageProcessingCallbackService {

	private final ImageProcessingCallbackRepository imageProcessingCallbackRepository;

	@Transactional
	public void savePendingCallback(String awsKey, ImageStatus status, String failReason) {
		imageProcessingCallbackRepository.upsert(awsKey, status.name(), failReason);
	}

	public void applyStatus(ImageFile image, ImageStatus status, String failReason) {
		if (status == ImageStatus.DONE) {
			image.markDone();
			if (image.getMimeType() != MimeType.IMAGE_GIF) {
				image.updateResizedKeys(image.getAwsKey());
			}
			return;
		}

		log.warn("이미지 lambda 처리 실패 [awsKey: {}] 사유: {}", image.getAwsKey(), failReason);
		image.markFailed();
		image.clearResizedKeys();
	}

	public void applyPendingCallback(ImageFile image) {
		imageProcessingCallbackRepository.findByAwsKey(image.getAwsKey())
			.ifPresent(callback -> {
				applyStatus(image, callback.getStatus(), callback.getFailReason());
				imageProcessingCallbackRepository.delete(callback);
			});
	}

	public void applyPendingCallbacks(Collection<? extends ImageFile> images) {
		images.forEach(this::applyPendingCallback);
	}

	/**
	 * 같은 awsKey를 공유하는 이미지들에 보류 콜백을 한 번에 반영한다.
	 *
	 * 한 장씩 applyPendingCallback을 돌리면 첫 장이 콜백 row를 지워버려서
	 * 나머지 장이 영영 PENDING으로 남는다. 전부 반영한 뒤에 지워야 한다.
	 */
	public void applySameKeyPendingCallback(List<? extends ImageFile> images) {
		if (images.isEmpty()) {
			return;
		}
		imageProcessingCallbackRepository.findByAwsKey(images.get(0).getAwsKey())
			.ifPresent(callback -> {
				images.forEach(image -> applyStatus(image, callback.getStatus(), callback.getFailReason()));
				imageProcessingCallbackRepository.delete(callback);
			});
	}
}
