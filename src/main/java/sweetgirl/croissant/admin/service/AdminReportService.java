package sweetgirl.croissant.admin.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.dto.request.AdminModerationActionRequest;
import sweetgirl.croissant.admin.dto.request.AdminReportReviewRequest;
import sweetgirl.croissant.admin.dto.response.AdminHighPriorityReportResponse;
import sweetgirl.croissant.admin.dto.response.AdminModerationActionResponse;
import sweetgirl.croissant.admin.dto.response.AdminReportResponse;
import sweetgirl.croissant.admin.dto.response.AdminUserReportDetailResponse;
import sweetgirl.croissant.admin.dto.response.AdminUserReportStatsResponse;
import sweetgirl.croissant.admin.exception.AdminException;
import sweetgirl.croissant.alarm.enums.ReportKind;
import sweetgirl.croissant.alarm.enums.ReportNotificationAction;
import sweetgirl.croissant.alarm.service.ReportNotificationService;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuAddReport;
import sweetgirl.croissant.menu.entity.MenuAddReportImageFile;
import sweetgirl.croissant.menu.entity.MenuImageFile;
import sweetgirl.croissant.menu.repository.MenuAddReportImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuAddReportRepository;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.report.dto.projection.UserReportTargetCountProjection;
import sweetgirl.croissant.report.entity.ModerationAction;
import sweetgirl.croissant.report.entity.UserReport;
import sweetgirl.croissant.report.enums.ReportReasonType;
import sweetgirl.croissant.report.enums.ReportTargetType;
import sweetgirl.croissant.report.enums.UserReportAction;
import sweetgirl.croissant.report.enums.UserReportStatus;
import sweetgirl.croissant.report.repository.ModerationActionRepository;
import sweetgirl.croissant.report.repository.UserReportRepository;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.entity.ReviewImageFile;
import sweetgirl.croissant.review.repository.ReviewImageFileRepository;
import sweetgirl.croissant.review.repository.ReviewRepository;
import sweetgirl.croissant.store.AddressParser;
import sweetgirl.croissant.store.dto.request.ReportImageRequest;
import sweetgirl.croissant.store.dto.request.StoreAddressReportRequest;
import sweetgirl.croissant.store.dto.request.StoreBusinessHoursReportRequest;
import sweetgirl.croissant.store.dto.request.StoreImageReportRequest;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreAddReport;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.entity.StoreReport;
import sweetgirl.croissant.store.entity.StoreReportImageFile;
import sweetgirl.croissant.store.enums.StoreReportStatus;
import sweetgirl.croissant.store.enums.StoreReportType;
import sweetgirl.croissant.store.repository.StoreAddReportRepository;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreReportImageFileRepository;
import sweetgirl.croissant.store.repository.StoreReportRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminReportService {

	private static final Logger log = LoggerFactory.getLogger(AdminReportService.class);
	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
	private static final int PRIORITY_LOOKBACK_HOURS = 24;
	private static final int REVIEW_HIDE_CANDIDATE_REPORTER_THRESHOLD = 3;
	private static final int SENSITIVE_REASON_PRIORITY_REPORTER_THRESHOLD = 2;
	private static final int MEMBER_REVIEW_CANDIDATE_TARGET_THRESHOLD = 3;
	private static final List<ReportReasonType> SENSITIVE_REASON_TYPES = List.of(
		ReportReasonType.PRIVACY,
		ReportReasonType.HATE,
		ReportReasonType.SEXUAL);
	private static final Comparator<AdminHighPriorityReportResponse> HIGH_PRIORITY_COMPARATOR = Comparator
		.comparingInt(AdminHighPriorityReportResponse::priorityScore).reversed()
		.thenComparing(Comparator.comparingLong(
			AdminHighPriorityReportResponse::sensitiveReasonReportCount).reversed())
		.thenComparing(Comparator.comparingLong(
			AdminHighPriorityReportResponse::reviewReportCount24h).reversed())
		.thenComparing(Comparator.comparingLong(
			AdminHighPriorityReportResponse::memberReviewReportCount24h).reversed())
		.thenComparing(AdminHighPriorityReportResponse::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
		.thenComparing(AdminHighPriorityReportResponse::reportId, Comparator.reverseOrder());

	private final StoreReportRepository storeReportRepository;
	private final StoreAddReportRepository storeAddReportRepository;
	private final MenuAddReportRepository menuAddReportRepository;
	private final UserReportRepository userReportRepository;
	private final ModerationActionRepository moderationActionRepository;
	private final StoreReportImageFileRepository storeReportImageFileRepository;
	private final MenuAddReportImageFileRepository menuAddReportImageFileRepository;
	private final StoreImageFileRepository storeImageFileRepository;
	private final StoreBusinessHourRepository storeBusinessHourRepository;
	private final MenuRepository menuRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final ReviewRepository reviewRepository;
	private final ReviewImageFileRepository reviewImageFileRepository;
	private final S3Service s3Service;
	private final ObjectMapper objectMapper;
	private final ReportNotificationService reportNotificationService;

	public Page<AdminReportResponse> getReports(String kind, String statusStr, int page, int size) {
		if ("STORE_ADD_REPORT".equals(kind)) {
			return getStoreAddReports(toStoreReportStatus(statusStr), page, size);
		}
		if ("MENU_ADD_REPORT".equals(kind)) {
			return getMenuAddReports(toStoreReportStatus(statusStr), page, size);
		}
		if ("USER_REPORT".equals(kind)) {
			return getUserReports(toUserReportStatus(statusStr), page, size);
		}
		if (kind != null && !"STORE_REPORT".equals(kind)) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_KIND);
		}
		return getStoreReports(toStoreReportStatus(statusStr), page, size);
	}

	public AdminUserReportDetailResponse getReportDetail(Long reportId, String kind) {
		if (!"USER_REPORT".equals(kind)) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_KIND);
		}

		UserReport report = userReportRepository.findByIdWithMembers(reportId)
			.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
		return AdminUserReportDetailResponse.of(report, toUserReportTarget(report));
	}

	public AdminUserReportStatsResponse getUserReportStats() {
		LocalDateTime since = LocalDateTime.now().minusHours(PRIORITY_LOOKBACK_HOURS);
		PriorityAggregation aggregation = aggregatePriorityMetrics(since, UserReportStatus.PENDING);

		return new AdminUserReportStatsResponse(
			userReportRepository.count(),
			userReportRepository.countByStatus(UserReportStatus.PENDING),
			userReportRepository.countByStatus(UserReportStatus.ACCEPTED),
			userReportRepository.countByStatus(UserReportStatus.REJECTED),
			userReportRepository.countByCreatedAtGreaterThanEqual(since),
			aggregation.reviewHideCandidateCount(),
			aggregation.sensitiveReasonPriorityCount(),
			aggregation.memberReviewCandidateCount(),
			PRIORITY_LOOKBACK_HOURS,
			REVIEW_HIDE_CANDIDATE_REPORTER_THRESHOLD,
			SENSITIVE_REASON_PRIORITY_REPORTER_THRESHOLD,
			MEMBER_REVIEW_CANDIDATE_TARGET_THRESHOLD,
			SENSITIVE_REASON_TYPES.stream()
				.map(Enum::name)
				.toList());
	}

	public Page<AdminHighPriorityReportResponse> getHighPriorityReports(String statusStr, int page, int size) {
		UserReportStatus status = toUserReportStatus(statusStr);
		LocalDateTime since = LocalDateTime.now().minusHours(PRIORITY_LOOKBACK_HOURS);
		PriorityAggregation aggregation = aggregatePriorityMetrics(since, status);
		List<UserReport> reports = status != null
			? userReportRepository.findByStatus(status)
			: userReportRepository.findAllWithMembers();

		List<AdminHighPriorityReportResponse> highPriorityReports = reports.stream()
			.map(report -> toHighPriorityReportResponse(report, aggregation))
			.filter(AdminHighPriorityReportResponse::highPriority)
			.sorted(HIGH_PRIORITY_COMPARATOR)
			.toList();

		PageRequest pageRequest = PageRequest.of(page, size);
		int start = Math.toIntExact(pageRequest.getOffset());
		if (start >= highPriorityReports.size()) {
			return new PageImpl<>(List.of(), pageRequest, highPriorityReports.size());
		}
		int end = Math.min(start + pageRequest.getPageSize(), highPriorityReports.size());
		return new PageImpl<>(highPriorityReports.subList(start, end), pageRequest, highPriorityReports.size());
	}

	@Transactional
	public AdminModerationActionResponse applyModerationAction(
			Long reportId,
			AdminModerationActionRequest request) {
		UserReport report = userReportRepository.findByIdWithMembers(reportId)
			.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
		if (report.getStatus() != UserReportStatus.ACCEPTED) {
			throw new AdminException(AdminErrorCode.REPORT_MODERATION_NOT_ALLOWED);
		}

		UserReportAction action = toModerationAction(request.action());
		String reason = request.moderationReason();
		applyReviewModeration(report, action, reason);
		report.applyModerationAction(action);

		ModerationAction savedAction = moderationActionRepository.save(request.toModerationAction(report, action));
		if (Boolean.TRUE.equals(request.notifyUser()) && shouldNotifyModeratedMember(action)) {
			reportNotificationService.notifyModerationAction(savedAction);
		}
		return AdminModerationActionResponse.from(savedAction);
	}

	@Transactional
	public void reviewReport(Long reportId, AdminReportReviewRequest request) {
		boolean accept = toAction(request.action());

		if ("MENU_ADD_REPORT".equals(request.kind())) {
			MenuAddReport report = menuAddReportRepository.findById(reportId)
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			if (report.getStatus() != StoreReportStatus.PENDING) {
				throw new AdminException(AdminErrorCode.REPORT_ALREADY_REVIEWED);
			}
			if (accept) {
				applyMenuAddReportApprove(report);
				report.approve();
			} else {
				cleanupMenuAddReportImages(report);
				report.reject();
			}
			reportNotificationService.notifyReportResult(
				report.getMember(), report.getStore(), report.getId(), ReportKind.MENU_ADD_REPORT,
				accept ? ReportNotificationAction.APPROVED : ReportNotificationAction.REJECTED,
				"메뉴 추가");
		} else if ("STORE_REPORT".equals(request.kind())) {
			StoreReport report = storeReportRepository.findById(reportId)
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			if (report.getStatus() != StoreReportStatus.PENDING) {
				throw new AdminException(AdminErrorCode.REPORT_ALREADY_REVIEWED);
			}
			if (accept) {
				applyStoreReportApprove(report);
				report.approve();
			} else {
				cleanupStoreReportImages(report);
				report.reject();
			}
			reportNotificationService.notifyReportResult(
				report.getMember(), report.getStore(), report.getId(), ReportKind.STORE_REPORT,
				accept ? ReportNotificationAction.APPROVED : ReportNotificationAction.REJECTED,
				subjectLabel(report.getRequestType()));
		} else if ("USER_REPORT".equals(request.kind())) {
			UserReport report = userReportRepository.findByIdWithMembers(reportId)
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			if (report.getStatus() != UserReportStatus.PENDING) {
				throw new AdminException(AdminErrorCode.REPORT_ALREADY_REVIEWED);
			}
			if (accept) {
				report.accept();
			} else {
				report.reject();
			}
			reportNotificationService.notifyUserReportResult(report);
		} else {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_KIND);
		}
	}

	// MenuAddReport 승인 — 새 Menu 생성 + 첨부 이미지를 menus/{menuId}로 S3 이동 후 menu_image_file 등록
	private void applyMenuAddReportApprove(MenuAddReport report) {
		Menu menu = menuRepository.save(report.toMenu());

		List<MenuAddReportImageFile> reportImages = menuAddReportImageFileRepository
			.findByMenuAddReportIdIn(List.of(report.getId()));
		moveImagesToMenu(menu, reportImages);
	}

	// MenuAddReport 거절 — temp S3 객체 정리 (DB 행은 이력 보관 위해 유지)
	private void cleanupMenuAddReportImages(MenuAddReport report) {
		menuAddReportImageFileRepository.findByMenuAddReportIdIn(List.of(report.getId()))
			.forEach(img -> safeDelete(img.getAwsKey()));
	}

	private void applyStoreReportApprove(StoreReport report) {
		StoreReportType type = report.getRequestType();
		switch (type) {
			case MOD_BAKERY_TIME -> applyBusinessHoursUpdate(report);
			case MOD_BAKERY_ADDRESS -> applyAddressUpdate(report);
			case MOD_BAKERY_IMG -> applyStoreImageChange(report);
			case MOD_BREAD -> applyMenuEdit(report);
			default -> throw new AdminException(AdminErrorCode.REPORT_INVALID_KIND);
		}
	}

	// StoreReport 거절 — 첨부 이미지 reports/ 객체 정리 (DB 행은 이력 유지)
	private void cleanupStoreReportImages(StoreReport report) {
		storeReportImageFileRepository.findByStoreReportIdIn(List.of(report.getId()))
			.forEach(img -> safeDelete(img.getAwsKey()));
	}

	// 영업시간 제보 승인 — 기존 영업시간 전체 삭제 후 제보 내용으로 재삽입
	private void applyBusinessHoursUpdate(StoreReport report) {
		StoreBusinessHoursReportRequest payload = parseMessage(report.getMessage(),
			StoreBusinessHoursReportRequest.class);
		Long storeId = report.getStore().getId();
		storeBusinessHourRepository.deleteByStoreId(storeId);

		storeBusinessHourRepository.saveAll(payload.toBusinessHours(report.getStore()));
	}

	// 주소 제보 승인 — Store 주소/위치/region 갱신
	private void applyAddressUpdate(StoreReport report) {
		StoreAddressReportRequest payload = parseMessage(report.getMessage(), StoreAddressReportRequest.class);
		String[] addr = AddressParser.parse(payload.address());
		Point location = GEOMETRY_FACTORY.createPoint(new Coordinate(payload.longitude(), payload.latitude()));
		report.getStore().update(null, payload.address(), location, addr[0], addr[1], addr[2],
			null, null, null);
	}

	// 가게 이미지 제보 승인 — deleteImageIds로 store_image_file·S3 삭제 + addImages는 stores/{storeId}로 이동 후 신규 등록
	private void applyStoreImageChange(StoreReport report) {
		StoreImageReportRequest payload = parseMessage(report.getMessage(), StoreImageReportRequest.class);
		Store store = report.getStore();
		Long storeId = store.getId();

		if (payload.deleteImageIds() != null && !payload.deleteImageIds().isEmpty()) {
			List<StoreImageFile> toDelete = storeImageFileRepository.findByIdInAndStoreId(
				payload.deleteImageIds(), storeId);
			toDelete.forEach(img -> safeDelete(img.getAwsKey()));
			storeImageFileRepository.deleteAll(toDelete);
		}

		if (payload.addImages() != null && !payload.addImages().isEmpty()) {
			Map<String, StoreReportImageFile> sourceByKey = storeReportImageFileRepository
				.findByStoreReportIdIn(List.of(report.getId())).stream()
				.collect(Collectors.toMap(StoreReportImageFile::getAwsKey, img -> img, (a, b) -> a));

			int nextOrder = currentMaxStoreImageOrder(storeId) + 1;
			List<StoreImageFile> newImages = new ArrayList<>();
			for (ReportImageRequest req : payload.addImages()) {
				StoreReportImageFile src = sourceByKey.get(req.awsKey());
				if (src == null) {
					log.warn("applyStoreImageChange: 대응 reports 이미지 없음 [reportId={}, key={}]",
						report.getId(), req.awsKey());
					continue;
				}
				String destKey = "stores/" + storeId + "/" + filenameFromKey(src.getAwsKey());
				s3Service.copyObject(src.getAwsKey(), destKey);
				newImages.add(src.toStoreImage(store, destKey, nextOrder++));
				safeDelete(src.getAwsKey());
			}
			storeImageFileRepository.saveAll(newImages);
		}
	}

	// 메뉴 수정 제보 승인 — menu.price 갱신 + 이미지 add/delete 적용
	private void applyMenuEdit(StoreReport report) {
		Menu menu = report.getMenu();
		if (menu == null) {
			log.warn("applyMenuEdit: 메뉴 참조 없음 [reportId={}]", report.getId());
			return;
		}
		MenuEditPayload payload = parseMessage(report.getMessage(), MenuEditPayload.class);

		if (payload.price() != null) {
			menu.update(null, payload.price(), null, null);
		}

		if (payload.deleteImageIds() != null && !payload.deleteImageIds().isEmpty()) {
			List<MenuImageFile> toDelete = menuImageFileRepository.findByIdInAndMenuId(
				payload.deleteImageIds(), menu.getId());
			toDelete.forEach(img -> safeDelete(img.getAwsKey()));
			menuImageFileRepository.deleteAll(toDelete);
		}

		if (payload.addImages() != null && !payload.addImages().isEmpty()) {
			Map<String, StoreReportImageFile> sourceByKey = storeReportImageFileRepository
				.findByStoreReportIdIn(List.of(report.getId())).stream()
				.collect(Collectors.toMap(StoreReportImageFile::getAwsKey, img -> img, (a, b) -> a));

			int nextOrder = currentMaxMenuImageOrder(menu.getId()) + 1;
			List<MenuImageFile> newImages = new ArrayList<>();
			for (ReportImageRequest req : payload.addImages()) {
				StoreReportImageFile src = sourceByKey.get(req.awsKey());
				if (src == null) {
					log.warn("applyMenuEdit: 대응 reports 이미지 없음 [reportId={}, key={}]",
						report.getId(), req.awsKey());
					continue;
				}
				String destKey = "menus/" + menu.getId() + "/" + filenameFromKey(src.getAwsKey());
				s3Service.copyObject(src.getAwsKey(), destKey);
				newImages.add(src.toMenuImage(menu, destKey, nextOrder++));
				safeDelete(src.getAwsKey());
			}
			menuImageFileRepository.saveAll(newImages);
		}
	}

	private void moveImagesToMenu(Menu menu, List<MenuAddReportImageFile> sources) {
		if (sources.isEmpty()) {
			return;
		}
		int order = 0;
		List<MenuImageFile> dest = new ArrayList<>();
		for (MenuAddReportImageFile src : sources) {
			String destKey = "menus/" + menu.getId() + "/" + filenameFromKey(src.getAwsKey());
			s3Service.copyObject(src.getAwsKey(), destKey);
			dest.add(src.toMenuImage(menu, destKey, order++));
			safeDelete(src.getAwsKey());
		}
		menuImageFileRepository.saveAll(dest);
	}

	private int currentMaxStoreImageOrder(Long storeId) {
		return storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(storeId).stream()
			.mapToInt(StoreImageFile::getDisplayOrder)
			.max()
			.orElse(-1);
	}

	private int currentMaxMenuImageOrder(Long menuId) {
		return menuImageFileRepository.findImagesByMenuId(menuId).stream()
			.mapToInt(MenuImageFile::getDisplayOrder)
			.max()
			.orElse(-1);
	}

	// S3 삭제 실패는 트랜잭션 롤백 사유로 부적합 — 고아 파일로 남더라도 로그만 남기고 진행
	private void safeDelete(String awsKey) {
		try {
			s3Service.deleteObject(awsKey);
		} catch (RuntimeException e) {
			log.warn("S3 삭제 실패 [key={}]: {}", awsKey, e.getMessage());
		}
	}

	private String filenameFromKey(String awsKey) {
		int idx = awsKey.lastIndexOf('/');
		return idx >= 0 ? awsKey.substring(idx + 1) : awsKey;
	}

	private <T> T parseMessage(String message, Class<T> type) {
		try {
			return objectMapper.readValue(message, type);
		} catch (JsonProcessingException e) {
			log.error("제보 메시지 파싱 실패: {}", message, e);
			throw new AdminException(AdminErrorCode.REPORT_PAYLOAD_INVALID);
		}
	}

	private String subjectLabel(StoreReportType type) {
		return switch (type) {
			case MOD_BAKERY_TIME -> "영업시간 수정";
			case MOD_BAKERY_ADDRESS -> "주소 수정";
			case MOD_BAKERY_IMG -> "가게 사진 수정";
			case MOD_BREAD -> "메뉴 수정";
		};
	}

	private Page<AdminReportResponse> getStoreReports(StoreReportStatus status, int page, int size) {
		Page<StoreReport> reports = status != null
			? storeReportRepository.findByStatusWithDetails(status, PageRequest.of(page, size))
			: storeReportRepository.findAllWithDetails(PageRequest.of(page, size));

		List<Long> reportIds = reports.getContent().stream().map(StoreReport::getId).toList();
		Map<Long, List<StoreReportImageFile>> imageMap = storeReportImageFileRepository
			.findByStoreReportIdIn(reportIds).stream()
			.collect(Collectors.groupingBy(img -> img.getStoreReport().getId()));

		return reports.map(r -> AdminReportResponse.ofStoreReport(r,
			imageMap.getOrDefault(r.getId(), List.of())));
	}

	private Page<AdminReportResponse> getStoreAddReports(StoreReportStatus status, int page, int size) {
		Page<StoreAddReport> reports = status != null
			? storeAddReportRepository.findByStatusWithDetails(status, PageRequest.of(page, size))
			: storeAddReportRepository.findAllWithDetails(PageRequest.of(page, size));

		return reports.map(AdminReportResponse::ofStoreAddReport);
	}

	private Page<AdminReportResponse> getMenuAddReports(StoreReportStatus status, int page, int size) {
		Page<MenuAddReport> reports = status != null
			? menuAddReportRepository.findByStatusWithDetails(status, PageRequest.of(page, size))
			: menuAddReportRepository.findAllWithDetails(PageRequest.of(page, size));

		List<Long> reportIds = reports.getContent().stream().map(MenuAddReport::getId).toList();
		Map<Long, List<MenuAddReportImageFile>> imageMap = menuAddReportImageFileRepository
			.findByMenuAddReportIdIn(reportIds).stream()
			.collect(Collectors.groupingBy(img -> img.getMenuAddReport().getId()));

		return reports.map(r -> AdminReportResponse.ofMenuAddReport(r,
			imageMap.getOrDefault(r.getId(), List.of())));
	}

	private Page<AdminReportResponse> getUserReports(UserReportStatus status, int page, int size) {
		Page<UserReport> reports = status != null
			? userReportRepository.findByStatus(status, PageRequest.of(page, size))
			: userReportRepository.findAll(PageRequest.of(page, size));

		return reports.map(AdminReportResponse::ofUserReport);
	}

	private Object toUserReportTarget(UserReport report) {
		if (report.getTargetType() == ReportTargetType.REVIEW) {
			Review review = reviewRepository.findByIdWithDetails(report.getTargetId())
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			List<String> images = reviewImageFileRepository.findByReviewIdOrderByDisplayOrderAscIdAsc(review.getId())
				.stream()
				.map(ReviewImageFile::getAwsKey)
				.toList();
			return AdminUserReportDetailResponse.ReviewTarget.of(review, images);
		}
		return AdminUserReportDetailResponse.MemberProfileTarget.from(report.getReportedMember());
	}

	private PriorityAggregation aggregatePriorityMetrics(LocalDateTime since, UserReportStatus status) {
		Map<TargetKey, Long> reviewReportCounts24h = toTargetCountMap(
			userReportRepository.findTargetReporterCountsSince(ReportTargetType.REVIEW, since, status));
		Map<TargetKey, Long> sensitiveReasonReportCounts = toTargetCountMap(
			userReportRepository.findSensitiveReasonReporterCounts(SENSITIVE_REASON_TYPES, status));
		Map<Long, Long> memberReviewCounts24h = userReportRepository
			.findMemberReviewTargetCountsSince(ReportTargetType.REVIEW, since, status).stream()
			.collect(Collectors.toMap(
				projection -> projection.getReportedMemberId(),
				projection -> nullToZero(projection.getReviewTargetCount())));

		return new PriorityAggregation(
			reviewReportCounts24h,
			sensitiveReasonReportCounts,
			memberReviewCounts24h);
	}

	private Map<TargetKey, Long> toTargetCountMap(
			List<? extends UserReportTargetCountProjection> counts) {
		Map<TargetKey, Long> result = new HashMap<>();
		for (var count : counts) {
			result.put(new TargetKey(count.getTargetType(), count.getTargetId()), nullToZero(count.getReporterCount()));
		}
		return result;
	}

	private AdminHighPriorityReportResponse toHighPriorityReportResponse(
			UserReport report,
			PriorityAggregation aggregation) {
		TargetKey targetKey = new TargetKey(report.getTargetType(), report.getTargetId());
		long reviewReportCount24h = aggregation.reviewReportCounts24h().getOrDefault(targetKey, 0L);
		long sensitiveReasonReportCount = aggregation.sensitiveReasonReportCounts().getOrDefault(targetKey, 0L);
		long memberReviewReportCount24h = aggregation.memberReviewCounts24h()
			.getOrDefault(report.getReportedMember().getId(), 0L);
		boolean temporaryHideCandidate = report.getTargetType() == ReportTargetType.REVIEW
			&& reviewReportCount24h >= REVIEW_HIDE_CANDIDATE_REPORTER_THRESHOLD;
		boolean sensitiveReasonPriority = sensitiveReasonReportCount >= SENSITIVE_REASON_PRIORITY_REPORTER_THRESHOLD;
		boolean memberReviewCandidate = memberReviewReportCount24h >= MEMBER_REVIEW_CANDIDATE_TARGET_THRESHOLD;
		List<String> priorityReasons = priorityReasons(
			temporaryHideCandidate,
			sensitiveReasonPriority,
			memberReviewCandidate);

		return AdminHighPriorityReportResponse.of(
			report,
			reviewReportCount24h,
			sensitiveReasonReportCount,
			memberReviewReportCount24h,
			temporaryHideCandidate,
			sensitiveReasonPriority,
			memberReviewCandidate,
			priorityScore(temporaryHideCandidate, sensitiveReasonPriority, memberReviewCandidate),
			priorityReasons);
	}

	private List<String> priorityReasons(
			boolean temporaryHideCandidate,
			boolean sensitiveReasonPriority,
			boolean memberReviewCandidate) {
		List<String> reasons = new ArrayList<>();
		if (sensitiveReasonPriority) {
			reasons.add("SENSITIVE_REASON_PRIORITY");
		}
		if (temporaryHideCandidate) {
			reasons.add("TEMPORARY_HIDE_CANDIDATE");
		}
		if (memberReviewCandidate) {
			reasons.add("MEMBER_REVIEW_CANDIDATE");
		}
		return reasons;
	}

	private int priorityScore(
			boolean temporaryHideCandidate,
			boolean sensitiveReasonPriority,
			boolean memberReviewCandidate) {
		int score = 0;
		if (sensitiveReasonPriority) {
			score += 100;
		}
		if (temporaryHideCandidate) {
			score += 50;
		}
		if (memberReviewCandidate) {
			score += 25;
		}
		return score;
	}

	private long nullToZero(Long value) {
		return value == null ? 0L : value;
	}

	private void applyReviewModeration(UserReport report, UserReportAction action, String reason) {
		if (action == UserReportAction.NONE) {
			return;
		}
		if (!isReviewModerationAction(action)) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
		}
		if (report.getTargetType() != ReportTargetType.REVIEW) {
			throw new AdminException(AdminErrorCode.REPORT_TARGET_TYPE_MISMATCH);
		}

		Review review = reviewRepository.findByIdWithDetails(report.getTargetId())
			.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_TARGET_NOT_FOUND));
		switch (action) {
			case HIDE_REVIEW -> {
				if (review.getDeletedAt() != null) {
					throw new AdminException(AdminErrorCode.REPORT_TARGET_NOT_FOUND);
				}
				review.hide(reason, null);
			}
			case DELETE_REVIEW -> review.softDelete();
			case RESTORE_REVIEW -> {
				if (report.getModerationAction() == UserReportAction.DELETE_REVIEW) {
					review.restoreModeration();
				} else {
					review.restoreHidden();
				}
			}
			default -> throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
		}
	}

	private boolean isReviewModerationAction(UserReportAction action) {
		return action == UserReportAction.HIDE_REVIEW
			|| action == UserReportAction.DELETE_REVIEW
			|| action == UserReportAction.RESTORE_REVIEW;
	}

	private boolean shouldNotifyModeratedMember(UserReportAction action) {
		return action == UserReportAction.HIDE_REVIEW
			|| action == UserReportAction.DELETE_REVIEW;
	}

	private UserReportAction toModerationAction(String action) {
		if (action == null || action.isBlank()) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
		}
		try {
			UserReportAction parsed = UserReportAction.valueOf(action.trim().toUpperCase(Locale.ROOT));
			if (parsed == UserReportAction.NONE || isReviewModerationAction(parsed)) {
				return parsed;
			}
		} catch (IllegalArgumentException e) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
		}
		throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
	}

	private StoreReportStatus toStoreReportStatus(String statusStr) {
		if (statusStr == null) {
			return null;
		}
		try {
			return StoreReportStatus.valueOf(statusStr.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_ACTION);
		}
	}

	private UserReportStatus toUserReportStatus(String statusStr) {
		if (statusStr == null) {
			return null;
		}
		try {
			return UserReportStatus.valueOf(statusStr.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_ACTION);
		}
	}

	private boolean toAction(String action) {
		if ("ACCEPT".equalsIgnoreCase(action)) {
			return true;
		}
		if ("REJECT".equalsIgnoreCase(action)) {
			return false;
		}
		throw new AdminException(AdminErrorCode.REPORT_INVALID_ACTION);
	}

	// StoreReport.message 가 JSON 직렬화된 MenuEditReportRequest 구조와 동일
	// 기존 DTO를 그대로 역직렬화하려면 menu 패키지 의존이 admin → menu 방향으로 생기는데 이미 다른 곳에서 사용 중이라 OK
	// 단, MenuEditReportRequest는 @Positive 등 validation annotation을 들고 있어 별도 page-record 정의가 더 깔끔
	private record MenuEditPayload(
			Integer price,
			List<Long> deleteImageIds,
			List<ReportImageRequest> addImages,
			String reason) {
	}

	private record TargetKey(ReportTargetType targetType, Long targetId) {
	}

	private record PriorityAggregation(
			Map<TargetKey, Long> reviewReportCounts24h,
			Map<TargetKey, Long> sensitiveReasonReportCounts,
			Map<Long, Long> memberReviewCounts24h) {

		long reviewHideCandidateCount() {
			return reviewReportCounts24h.values().stream()
				.filter(count -> count >= REVIEW_HIDE_CANDIDATE_REPORTER_THRESHOLD)
				.count();
		}

		long sensitiveReasonPriorityCount() {
			return sensitiveReasonReportCounts.values().stream()
				.filter(count -> count >= SENSITIVE_REASON_PRIORITY_REPORTER_THRESHOLD)
				.count();
		}

		long memberReviewCandidateCount() {
			return memberReviewCounts24h.values().stream()
				.filter(count -> count >= MEMBER_REVIEW_CANDIDATE_TARGET_THRESHOLD)
				.count();
		}
	}
}
