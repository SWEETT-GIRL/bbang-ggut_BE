package sweetgirl.croissant.external.aws.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.entity.ImageFile;
import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.external.aws.dto.ImageStatusUpdateRequest;
import sweetgirl.croissant.external.aws.exception.S3ErrorCode;
import sweetgirl.croissant.external.aws.exception.S3StorageException;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

@Service
@RequiredArgsConstructor
public class ImageCallbackService {

	private final StoreImageFileRepository storeImageFileRepository;
	private final MenuImageFileRepository menuImageFileRepository;

	@Transactional
	public void updateImageStatus(ImageStatusUpdateRequest request) {
		String awsKey = request.getAwsKey();
		ImageStatus status = request.getStatus();

		if (status != ImageStatus.DONE && status != ImageStatus.FAILED) {
			throw new S3StorageException(S3ErrorCode.INVALID_STATUS_VALUE);
		}

		ImageFile image = findByAwsKey(awsKey);
		if (status == ImageStatus.DONE) {
			image.markDone();
		} else {
			image.markFailed();
		}
	}

	private ImageFile findByAwsKey(String awsKey) {
		if (awsKey.startsWith("stores/")) {
			return storeImageFileRepository.findByAwsKey(awsKey)
				.orElseThrow(() -> new S3StorageException(S3ErrorCode.IMAGE_NOT_FOUND));
		}
		if (awsKey.startsWith("menus/")) {
			return menuImageFileRepository.findByAwsKey(awsKey)
				.orElseThrow(() -> new S3StorageException(S3ErrorCode.IMAGE_NOT_FOUND));
		}
		throw new S3StorageException(S3ErrorCode.IMAGE_NOT_FOUND);
	}
}
