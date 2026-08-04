package sweetgirl.croissant.external.aws.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
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
	private final BreadRecordPhotoRepository breadRecordPhotoRepository;
	private final ImageProcessingCallbackService imageProcessingCallbackService;

	@Transactional
	public void updateImageStatus(ImageStatusUpdateRequest request) {
		String awsKey = request.getAwsKey();
		ImageStatus status = request.getStatus();

		if (status != ImageStatus.DONE && status != ImageStatus.FAILED) {
			throw new S3StorageException(S3ErrorCode.INVALID_STATUS_VALUE);
		}

		Optional<ImageFile> image = findByAwsKey(awsKey);
		if (image.isPresent()) {
			imageProcessingCallbackService.applyStatus(image.get(), status, request.getFailReason());
			return;
		}

		log.info("이미지 row 생성 전 lambda 콜백 수신 [awsKey: {}, status: {}]", awsKey, status);
		imageProcessingCallbackService.savePendingCallback(awsKey, status, request.getFailReason());
	}

	private Optional<ImageFile> findByAwsKey(String awsKey) {
		if (awsKey.startsWith(S3Folder.STORES.prefix())) {
			return storeImageFileRepository.findByAwsKey(awsKey)
				.map(ImageFile.class::cast);
		}
		if (awsKey.startsWith(S3Folder.MENUS.prefix())) {
			return menuImageFileRepository.findByAwsKey(awsKey)
				.map(ImageFile.class::cast);
		}
		if (awsKey.startsWith(S3Folder.REPORTS.prefix())) {
			var storeReportImage = storeContributionReportImageFileRepository.findByAwsKey(awsKey);
			if (storeReportImage.isPresent()) {
				return storeReportImage.map(ImageFile.class::cast);
			}
			return menuContributionReportImageFileRepository.findByAwsKey(awsKey)
				.map(ImageFile.class::cast);
		}
		if (awsKey.startsWith(S3Folder.REVIEWS.prefix())) {
			return reviewImageFileRepository.findByAwsKey(awsKey)
				.map(ImageFile.class::cast);
		}
		if (awsKey.startsWith(S3Folder.COURSES.prefix())) {
			return courseImageFileRepository.findByAwsKey(awsKey)
				.map(ImageFile.class::cast);
		}
		if (awsKey.startsWith(S3Folder.BREAD_RECORDS.prefix())) {
			return breadRecordPhotoRepository.findByAwsKey(awsKey)
				.map(ImageFile.class::cast);
		}
		if (awsKey.startsWith(S3Folder.MENU_ADD_REPORTS.prefix())) {
			return menuContributionReportImageFileRepository.findByAwsKey(awsKey)
				.map(ImageFile.class::cast);
		}
		throw new S3StorageException(S3ErrorCode.IMAGE_NOT_FOUND);
	}
}
