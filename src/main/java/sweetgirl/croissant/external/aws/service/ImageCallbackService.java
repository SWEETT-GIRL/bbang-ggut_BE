package sweetgirl.croissant.external.aws.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.common.entity.ImageFile;
import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.contribution.repository.MenuContributionReportImageFileRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportImageFileRepository;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.external.aws.dto.ImageStatusUpdateRequest;
import sweetgirl.croissant.external.aws.enums.S3Folder;
import sweetgirl.croissant.external.aws.exception.S3ErrorCode;
import sweetgirl.croissant.external.aws.exception.S3StorageException;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.review.repository.ReviewImageFileRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageCallbackService {

	private final StoreImageFileRepository storeImageFileRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final StoreContributionReportImageFileRepository storeContributionReportImageFileRepository;
	private final MenuContributionReportImageFileRepository menuContributionReportImageFileRepository;
	private final ReviewImageFileRepository reviewImageFileRepository;
	private final CourseImageFileRepository courseImageFileRepository;

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
			image.updateResizedKeys(awsKey);
		} else {
			log.warn("이미지 lambda 처리 실패 [awsKey: {}] 사유: {}", request.getAwsKey(), request.getFailReason());
			image.markFailed();
		}
	}

	private ImageFile findByAwsKey(String awsKey) {
		if (awsKey.startsWith(S3Folder.STORES.prefix())) {
			return storeImageFileRepository.findByAwsKey(awsKey)
				.orElseThrow(() -> new S3StorageException(S3ErrorCode.IMAGE_NOT_FOUND));
		}
		if (awsKey.startsWith(S3Folder.MENUS.prefix())) {
			return menuImageFileRepository.findByAwsKey(awsKey)
				.orElseThrow(() -> new S3StorageException(S3ErrorCode.IMAGE_NOT_FOUND));
		}
		if (awsKey.startsWith(S3Folder.REPORTS.prefix())) {
			var storeReportImage = storeContributionReportImageFileRepository.findByAwsKey(awsKey);
			if (storeReportImage.isPresent()) {
				return storeReportImage.get();
			}
			return menuContributionReportImageFileRepository.findByAwsKey(awsKey)
				.orElseThrow(() -> new S3StorageException(S3ErrorCode.IMAGE_NOT_FOUND));
		}
		if (awsKey.startsWith(S3Folder.REVIEWS.prefix())) {
			return reviewImageFileRepository.findByAwsKey(awsKey)
				.orElseThrow(() -> new S3StorageException(S3ErrorCode.IMAGE_NOT_FOUND));
		}
		if (awsKey.startsWith(S3Folder.COURSES.prefix())) {
			return courseImageFileRepository.findByAwsKey(awsKey)
				.orElseThrow(() -> new S3StorageException(S3ErrorCode.IMAGE_NOT_FOUND));
		}
		if (awsKey.startsWith(S3Folder.MENU_ADD_REPORTS.prefix())) {
			return menuContributionReportImageFileRepository.findByAwsKey(awsKey)
				.orElseThrow(() -> new S3StorageException(S3ErrorCode.IMAGE_NOT_FOUND));
		}
		throw new S3StorageException(S3ErrorCode.IMAGE_NOT_FOUND);
	}
}
