package sweetgirl.croissant.external.aws.service;

import java.util.Collection;

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
		imageProcessingCallbackRepository.findByAwsKey(awsKey)
			.ifPresentOrElse(
				callback -> callback.update(status, failReason),
				() -> imageProcessingCallbackRepository.save(new ImageProcessingCallback(awsKey, status, failReason)));
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
}
