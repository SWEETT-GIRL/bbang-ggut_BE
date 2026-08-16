package sweetgirl.croissant.external.aws.service;

import java.util.List;
import java.util.Optional;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.common.entity.ImageFile;
import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.contribution.repository.MenuContributionReportImageFileRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportImageFileRepository;
import sweetgirl.croissant.course.code.CourseCacheNames;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.external.aws.dto.ImageStatusUpdateRequest;
import sweetgirl.croissant.external.aws.enums.S3Folder;
import sweetgirl.croissant.external.aws.exception.S3ErrorCode;
import sweetgirl.croissant.external.aws.exception.S3StorageException;
import sweetgirl.croissant.external.aws.metrics.ImagePipelineMetrics;
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
	private final ImagePipelineMetrics imagePipelineMetrics;

	/** 코스 커버 이미지 콜백인지 — S3 키 접두사(courses/)로 판별한다. */
	private static final String IS_COURSE_COVER_CALLBACK = "#request.awsKey.startsWith("
		+ "T(sweetgirl.croissant.external.aws.enums.S3Folder).COURSES.prefix())";

	/**
	 * 코스 커버 이미지는 처리 완료(DONE) 전까지 조회에서 빠지므로(@SQLRestriction), 콜백으로 상태가 바뀌면
	 * 공개 코스 목록 캐시를 비운다. 다른 이미지(가게·메뉴·빵 기록)의 콜백은 목록과 무관하므로 건드리지 않는다.
	 */
	@CacheEvict(cacheNames = CourseCacheNames.PUBLIC_LIST, allEntries = true, condition = IS_COURSE_COVER_CALLBACK)
	@Transactional
	public void updateImageStatus(ImageStatusUpdateRequest request) {
		String awsKey = request.getAwsKey();
		ImageStatus status = request.getStatus();

		if (status != ImageStatus.DONE && status != ImageStatus.FAILED) {
			throw new S3StorageException(S3ErrorCode.INVALID_STATUS_VALUE);
		}
		validateSupportedPrefix(awsKey);

		// 콜백을 먼저 저장해야 이미지 row 생성 트랜잭션과 동시에 실행돼도 상태가 유실되지 않는다.
		imageProcessingCallbackService.savePendingCallback(awsKey, status, request.getFailReason());

		// 빵 기록 사진만 같은 키가 여러 row일 수 있다(전체 교체가 같은 키를 다시 싣는다).
		// 단건 조회는 2건을 만나는 순간 예외로 죽어 콜백 전체가 유실되므로 있는 만큼 모두 반영한다.
		if (awsKey.startsWith(S3Folder.BREAD_RECORDS.prefix())) {
			List<BreadRecordPhoto> photos = breadRecordPhotoRepository.findAllByAwsKey(awsKey);
			if (!photos.isEmpty()) {
				imageProcessingCallbackService.applySameKeyPendingCallback(photos);
				imagePipelineMetrics.recordCallback(status, photos.get(0).getMimeType());
				return;
			}
		} else {
			Optional<ImageFile> image = findByAwsKey(awsKey);
			if (image.isPresent()) {
				imageProcessingCallbackService.applyPendingCallback(image.get());
				imagePipelineMetrics.recordCallback(status, image.get().getMimeType());
				return;
			}
		}

		// 반영할 행이 없어 보류 테이블에만 남은 경우. 유실되지는 않지만 급증하면 등록 API 실패를 의심한다.
		// MIME은 행에만 있으므로 이 경로에서는 알 수 없다.
		imagePipelineMetrics.recordOrphanCallback();
		imagePipelineMetrics.recordCallback(status, null);
		log.info("이미지 row 생성 전 lambda 콜백 수신 [awsKey: {}, status: {}]", awsKey, status);
	}

	private void validateSupportedPrefix(String awsKey) {
		boolean supported = awsKey.startsWith(S3Folder.STORES.prefix())
			|| awsKey.startsWith(S3Folder.MENUS.prefix())
			|| awsKey.startsWith(S3Folder.REPORTS.prefix())
			|| awsKey.startsWith(S3Folder.REVIEWS.prefix())
			|| awsKey.startsWith(S3Folder.COURSES.prefix())
			|| awsKey.startsWith(S3Folder.BREAD_RECORDS.prefix())
			|| awsKey.startsWith(S3Folder.MENU_ADD_REPORTS.prefix());
		if (!supported) {
			throw new S3StorageException(S3ErrorCode.IMAGE_NOT_FOUND);
		}
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
		if (awsKey.startsWith(S3Folder.MENU_ADD_REPORTS.prefix())) {
			return menuContributionReportImageFileRepository.findByAwsKey(awsKey)
				.map(ImageFile.class::cast);
		}
		return Optional.empty();
	}
}
