package sweetgirl.croissant.admin.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.dto.projection.AbuseReportTargetCountProjection;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.abuse.enums.AbuseReportAction;
import sweetgirl.croissant.abuse.enums.AbuseReportReasonType;
import sweetgirl.croissant.abuse.enums.AbuseReportStatus;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;
import sweetgirl.croissant.abuse.repository.ModerationActionRepository;
import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.dto.request.AdminModerationActionRequest;
import sweetgirl.croissant.admin.dto.request.AdminReportReviewRequest;
import sweetgirl.croissant.admin.dto.response.AdminAbuseReportDetailResponse;
import sweetgirl.croissant.admin.dto.response.AdminAbuseReportStatsResponse;
import sweetgirl.croissant.admin.dto.response.AdminHighPriorityReportResponse;
import sweetgirl.croissant.admin.dto.response.AdminModerationActionResponse;
import sweetgirl.croissant.admin.dto.response.AdminReportDetailResponse;
import sweetgirl.croissant.admin.dto.response.AdminReportResponse;
import sweetgirl.croissant.admin.exception.AdminException;
import sweetgirl.croissant.alarm.enums.ReportNotificationAction;
import sweetgirl.croissant.alarm.service.ReportNotificationService;
import sweetgirl.croissant.common.enums.ReportKind;
import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.contribution.entity.MenuContributionReport;
import sweetgirl.croissant.contribution.entity.MenuContributionReportImageFile;
import sweetgirl.croissant.contribution.entity.StoreContributionReport;
import sweetgirl.croissant.contribution.entity.StoreContributionReportImageFile;
import sweetgirl.croissant.contribution.enums.ContributionReportStatus;
import sweetgirl.croissant.contribution.enums.ContributionReportType;
import sweetgirl.croissant.contribution.enums.ContributionTargetType;
import sweetgirl.croissant.contribution.repository.ContributionReportRepository;
import sweetgirl.croissant.contribution.repository.MenuContributionReportImageFileRepository;
import sweetgirl.croissant.contribution.repository.MenuContributionReportRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportImageFileRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportRepository;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.opensearch.event.StoreAddressChangedEvent;
import sweetgirl.croissant.external.opensearch.event.StoreMenusChangedEvent;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuImageFile;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
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
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.enums.StoreCategory;
import sweetgirl.croissant.store.enums.StoreReportStatus;
import sweetgirl.croissant.store.enums.StoreReportType;
import sweetgirl.croissant.store.enums.StoreSource;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

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
	private static final List<AbuseReportReasonType> SENSITIVE_REASON_TYPES = List.of(
		AbuseReportReasonType.PRIVACY,
		AbuseReportReasonType.HATE,
		AbuseReportReasonType.SEXUAL);
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

	private final StoreRepository storeRepository;
	private final ContributionReportRepository contributionReportRepository;
	private final StoreContributionReportRepository storeContributionReportRepository;
	private final MenuContributionReportRepository menuContributionReportRepository;
	private final AbuseReportRepository abuseReportRepository;
	private final ModerationActionRepository moderationActionRepository;
	private final StoreContributionReportImageFileRepository storeContributionReportImageFileRepository;
	private final MenuContributionReportImageFileRepository menuContributionReportImageFileRepository;
	private final StoreImageFileRepository storeImageFileRepository;
	private final StoreBusinessHourRepository storeBusinessHourRepository;
	private final MenuRepository menuRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final ReviewRepository reviewRepository;
	private final ReviewImageFileRepository reviewImageFileRepository;
	private final S3Service s3Service;
	private final ObjectMapper objectMapper;
	private final ReportNotificationService reportNotificationService;
	private final ApplicationEventPublisher eventPublisher;

	public Page<AdminReportResponse> getReports(String kind, String statusStr, int page, int size) {
		if ("STORE_ADD_REPORT".equals(kind)) {
			return getStoreContributionReports(ContributionReportType.ADD, toContributionReportStatus(statusStr),
				page, size);
		}
		if ("MENU_ADD_REPORT".equals(kind)) {
			return getMenuContributionReports(ContributionReportType.ADD, toContributionReportStatus(statusStr),
				page, size);
		}
		if ("MENU_REPORT".equals(kind)) {
			return getMenuContributionReports(ContributionReportType.UPDATE, toContributionReportStatus(statusStr),
				page, size);
		}
		if ("ABUSE_REPORT".equals(kind)) {
			return getAbuseReports(toAbuseReportStatus(statusStr), page, size);
		}
		if (kind != null && !"STORE_REPORT".equals(kind)) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_KIND);
		}
		return getStoreContributionReports(ContributionReportType.UPDATE, toContributionReportStatus(statusStr),
			page, size);
	}

	public AdminReportDetailResponse getReportDetail(Long reportId, String kind) {
		if ("STORE_ADD_REPORT".equals(kind)) {
			StoreContributionReport contribution = storeContributionReportRepository
				.findByContributionReportId(reportId)
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			return AdminReportDetailResponse.ofReport(AdminReportResponse.ofStoreContributionReport(
				contribution.getContributionReport(), contribution, List.of()));
		}
		if ("STORE_REPORT".equals(kind)) {
			StoreContributionReport contribution = storeContributionReportRepository
				.findByContributionReportId(reportId)
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			List<StoreContributionReportImageFile> images = storeContributionReportImageFileRepository
				.findByStoreContributionReportIdIn(List.of(contribution.getId()));
			return AdminReportDetailResponse.ofReport(AdminReportResponse.ofStoreContributionReport(
				contribution.getContributionReport(), contribution, images));
		}
		if ("MENU_ADD_REPORT".equals(kind)) {
			MenuContributionReport contribution = menuContributionReportRepository.findByContributionReportId(reportId)
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			List<MenuContributionReportImageFile> images = menuContributionReportImageFileRepository
				.findByMenuContributionReportIdIn(List.of(contribution.getId()));
			return AdminReportDetailResponse.ofReport(AdminReportResponse.ofMenuContributionReport(
				contribution.getContributionReport(), contribution, images));
		}
		if ("MENU_REPORT".equals(kind)) {
			MenuContributionReport contribution = menuContributionReportRepository.findByContributionReportId(reportId)
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			List<MenuContributionReportImageFile> images = menuContributionReportImageFileRepository
				.findByMenuContributionReportIdIn(List.of(contribution.getId()));
			return AdminReportDetailResponse.ofReport(AdminReportResponse.ofMenuContributionReport(
				contribution.getContributionReport(), contribution, images));
		}
		if (!"ABUSE_REPORT".equals(kind)) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_KIND);
		}

		AbuseReport report = abuseReportRepository.findByIdWithMembers(reportId)
			.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
		return AdminReportDetailResponse.ofAbuseReport(report, toAbuseReportTarget(report));
	}

	public AdminAbuseReportStatsResponse getAbuseReportStats() {
		LocalDateTime since = LocalDateTime.now().minusHours(PRIORITY_LOOKBACK_HOURS);
		PriorityAggregation aggregation = aggregatePriorityMetrics(since, AbuseReportStatus.PENDING);

		return new AdminAbuseReportStatsResponse(
			abuseReportRepository.count(),
			abuseReportRepository.countByStatus(AbuseReportStatus.PENDING),
			abuseReportRepository.countByStatus(AbuseReportStatus.ACCEPTED),
			abuseReportRepository.countByStatus(AbuseReportStatus.REJECTED),
			abuseReportRepository.countByCreatedAtGreaterThanEqual(since),
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
		AbuseReportStatus status = toAbuseReportStatus(statusStr);
		LocalDateTime since = LocalDateTime.now().minusHours(PRIORITY_LOOKBACK_HOURS);
		PriorityAggregation aggregation = aggregatePriorityMetrics(since, status);
		List<AbuseReport> reports = status != null
			? abuseReportRepository.findByStatus(status)
			: abuseReportRepository.findAllWithMembers();

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
		AbuseReport report = abuseReportRepository.findByIdWithMembers(reportId)
			.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
		if (report.getStatus() != AbuseReportStatus.ACCEPTED) {
			throw new AdminException(AdminErrorCode.REPORT_MODERATION_NOT_ALLOWED);
		}

		AbuseReportAction action = toModerationAction(request.action());
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
			MenuContributionReport contribution = menuContributionReportRepository.findByContributionReportId(reportId)
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			assertContributionPending(contribution.getContributionReport(), contribution.getStatus());
			if (accept) {
				applyMenuAddReportApprove(contribution);
				contribution.approve();
				contribution.getContributionReport().approve();
			} else {
				cleanupMenuAddReportImages(contribution);
				contribution.reject();
				contribution.getContributionReport().reject();
			}
			reportNotificationService.notifyContributionReportResult(
				contribution.getMember(), contribution.getStore(), null, contribution.getContributionReport().getId(),
				contribution.getContributionReport().getId(),
				ReportKind.MENU_ADD_REPORT,
				accept ? ReportNotificationAction.APPROVED : ReportNotificationAction.REJECTED,
				"메뉴 추가");
		} else if ("STORE_ADD_REPORT".equals(request.kind())) {
			StoreContributionReport contribution = storeContributionReportRepository
				.findByContributionReportId(reportId)
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			assertContributionPending(contribution.getContributionReport(), contribution.getStatus());
			Store store = null;
			if (accept) {
				store = applyStoreAddReportApprove(contribution);
				contribution.approve();
				contribution.getContributionReport().approve();
			} else {
				contribution.reject();
				contribution.getContributionReport().reject();
			}
			reportNotificationService.notifyContributionReportResult(
				contribution.getMember(), store, contribution.getName(), contribution.getContributionReport().getId(),
				contribution.getContributionReport().getId(),
				ReportKind.STORE_ADD_REPORT,
				accept ? ReportNotificationAction.APPROVED : ReportNotificationAction.REJECTED,
				"가게 추가");
		} else if ("STORE_REPORT".equals(request.kind())) {
			StoreContributionReport contribution = storeContributionReportRepository
				.findByContributionReportId(reportId)
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			assertContributionPending(contribution.getContributionReport(), contribution.getStatus());
			if (accept) {
				applyStoreReportApprove(contribution);
				contribution.approve();
				contribution.getContributionReport().approve();
			} else {
				cleanupStoreReportImages(contribution);
				contribution.reject();
				contribution.getContributionReport().reject();
			}
			reportNotificationService.notifyContributionReportResult(
				contribution.getMember(), contribution.getStore(), null, contribution.getContributionReport().getId(),
				contribution.getContributionReport().getId(),
				ReportKind.STORE_REPORT,
				accept ? ReportNotificationAction.APPROVED : ReportNotificationAction.REJECTED,
				subjectLabel(toStoreReportType(contribution.getRequestType())));
		} else if ("MENU_REPORT".equals(request.kind())) {
			MenuContributionReport contribution = menuContributionReportRepository.findByContributionReportId(reportId)
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			assertContributionPending(contribution.getContributionReport(), contribution.getStatus());
			if (accept) {
				applyMenuReportApprove(contribution);
				contribution.approve();
				contribution.getContributionReport().approve();
			} else {
				cleanupMenuContributionImages(contribution);
				contribution.reject();
				contribution.getContributionReport().reject();
			}
			reportNotificationService.notifyContributionReportResult(
				contribution.getMember(), contribution.getStore(), null, contribution.getContributionReport().getId(),
				contribution.getContributionReport().getId(),
				ReportKind.STORE_REPORT,
				accept ? ReportNotificationAction.APPROVED : ReportNotificationAction.REJECTED,
				subjectLabel(toStoreReportType(contribution.getRequestType())));
		} else if ("ABUSE_REPORT".equals(request.kind())) {
			AbuseReport report = abuseReportRepository.findByIdWithMembers(reportId)
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			if (report.getStatus() != AbuseReportStatus.PENDING) {
				throw new AdminException(AdminErrorCode.REPORT_ALREADY_REVIEWED);
			}
			if (accept) {
				report.accept();
			} else {
				report.reject();
			}
			reportNotificationService.notifyAbuseReportResult(report);
		} else {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_KIND);
		}
	}

	// 메뉴 추가 승인 — 새 Menu 생성 + 첨부 이미지를 menus/{menuId}로 S3 이동 후 menu_image_file 등록
	private void applyMenuAddReportApprove(MenuContributionReport report) {
		Menu menu = menuRepository.save(report.toMenu());

		List<MenuContributionReportImageFile> reportImages = menuContributionReportImageFileRepository
			.findByMenuContributionReportIdIn(List.of(report.getId()));
		moveImagesToMenu(menu, reportImages);
		eventPublisher.publishEvent(new StoreMenusChangedEvent(menu.getStore().getId()));
	}

	// 메뉴 추가 거절 — temp S3 객체 정리 (DB 행은 이력 보관 위해 유지)
	private void cleanupMenuAddReportImages(MenuContributionReport report) {
		menuContributionReportImageFileRepository.findByMenuContributionReportIdIn(List.of(report.getId()))
			.forEach(img -> safeDelete(img.getAwsKey()));
	}

	private Store applyStoreAddReportApprove(StoreContributionReport report) {
		if (report.getNaverPlaceId() != null && !report.getNaverPlaceId().isBlank()) {
			return storeRepository.findByNaverPlaceId(report.getNaverPlaceId())
				.orElseGet(() -> findOrCreateStoreFromAddReport(report));
		}
		return findOrCreateStoreFromAddReport(report);
	}

	private Store findOrCreateStoreFromAddReport(StoreContributionReport report) {
		return findActiveStoreBySameNameAndAddress(report)
			.map(store -> {
				if (report.getNaverPlaceId() != null && !report.getNaverPlaceId().isBlank()
					&& store.getNaverPlaceId() == null) {
					store.markNaverMatched(report.getNaverPlaceId());
				}
				return store;
			})
			.orElseGet(() -> createStoreFromAddReport(report));
	}

	private Store createStoreFromAddReport(StoreContributionReport report) {
		String[] addr = AddressParser.parse(report.getAddress());
		Store store = Store.builder()
			.name(report.getName())
			.address(report.getAddress())
			.region1st(addr[0])
			.region2nd(addr[1])
			.region3rd(addr[2])
			.location(report.getLocation())
			.phone(report.getPhone())
			.naverPlaceId(report.getNaverPlaceId())
			.source(StoreSource.ADMIN_REGISTERED)
			.category(StoreCategory.WESTERN)
			.build();
		return storeRepository.save(store);
	}

	private Optional<Store> findActiveStoreBySameNameAndAddress(StoreContributionReport report) {
		String compactName = compactName(report.getName());
		String normalizedAddress = normalizeAddressForDuplicate(report.getAddress());
		if (compactName.isBlank() || normalizedAddress.isBlank()) {
			return Optional.empty();
		}
		return storeRepository.findActiveStoresByCompactName(compactName)
			.stream()
			.filter(store -> normalizedAddress.equals(normalizeAddressForDuplicate(store.getAddress())))
			.findFirst();
	}

	private String compactName(String value) {
		if (value == null) {
			return "";
		}
		return value.toLowerCase().replaceAll("\\s+", "");
	}

	private String normalizeAddressForDuplicate(String value) {
		if (value == null) {
			return "";
		}
		return value.toLowerCase()
			.replaceAll("(?:지하)?\\d+층", "")
			.replaceAll("\\d+호", "")
			.replaceAll("\\s+", "")
			.trim();
	}

	private void applyStoreReportApprove(StoreContributionReport report) {
		StoreReportType type = toStoreReportType(report.getRequestType());
		switch (type) {
			case MOD_BAKERY_TIME -> applyBusinessHoursUpdate(report);
			case MOD_BAKERY_ADDRESS -> applyAddressUpdate(report);
			case MOD_BAKERY_IMG -> applyStoreImageChange(report);
			case MOD_BREAD -> throw new AdminException(AdminErrorCode.REPORT_INVALID_KIND);
			default -> throw new AdminException(AdminErrorCode.REPORT_INVALID_KIND);
		}
	}

	// 가게 수정 거절 — 첨부 이미지 reports/ 객체 정리 (DB 행은 이력 유지)
	private void cleanupStoreReportImages(StoreContributionReport report) {
		storeContributionReportImageFileRepository.findByStoreContributionReportIdIn(List.of(report.getId()))
			.forEach(img -> safeDelete(img.getAwsKey()));
	}

	// 영업시간 제보 승인 — 기존 영업시간 전체 삭제 후 제보 내용으로 재삽입
	private void applyBusinessHoursUpdate(StoreContributionReport report) {
		StoreBusinessHoursReportRequest payload = parseMessage(report.getMessage(),
			StoreBusinessHoursReportRequest.class);
		Long storeId = report.getStore().getId();
		storeBusinessHourRepository.deleteByStoreId(storeId);

		storeBusinessHourRepository.saveAll(payload.toBusinessHours(report.getStore()));
	}

	// 주소 제보 승인 — Store 주소/위치/region 갱신
	private void applyAddressUpdate(StoreContributionReport report) {
		StoreAddressReportRequest payload = parseMessage(report.getMessage(), StoreAddressReportRequest.class);
		String[] addr = AddressParser.parse(payload.address());
		Point location = GEOMETRY_FACTORY.createPoint(new Coordinate(payload.longitude(), payload.latitude()));
		report.getStore().update(null, payload.address(), location, addr[0], addr[1], addr[2],
			null, null, null);
		eventPublisher.publishEvent(new StoreAddressChangedEvent(report.getStore().getId()));
	}

	// 가게 이미지 제보 승인 — deleteImageIds로 store_image_file·S3 삭제 + addImages는 stores/{storeId}로 이동 후 신규 등록
	private void applyStoreImageChange(StoreContributionReport report) {
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
			Map<String, StoreContributionReportImageFile> sourceByKey = storeContributionReportImageFileRepository
				.findByStoreContributionReportIdIn(List.of(report.getId())).stream()
				.collect(Collectors.toMap(StoreContributionReportImageFile::getAwsKey, img -> img, (a, b) -> a));

			int nextOrder = currentMaxStoreImageOrder(storeId) + 1;
			List<StoreImageFile> newImages = new ArrayList<>();
			for (ReportImageRequest req : payload.addImages()) {
				StoreContributionReportImageFile src = sourceByKey.get(req.awsKey());
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
	private void applyMenuReportApprove(MenuContributionReport report) {
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
			Map<String, MenuContributionReportImageFile> sourceByKey = menuContributionReportImageFileRepository
				.findByMenuContributionReportIdIn(List.of(report.getId())).stream()
				.collect(Collectors.toMap(MenuContributionReportImageFile::getAwsKey, img -> img, (a, b) -> a));

			int nextOrder = currentMaxMenuImageOrder(menu.getId()) + 1;
			List<MenuImageFile> newImages = new ArrayList<>();
			for (ReportImageRequest req : payload.addImages()) {
				MenuContributionReportImageFile src = sourceByKey.get(req.awsKey());
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
		eventPublisher.publishEvent(new StoreMenusChangedEvent(menu.getStore().getId()));
	}

	private void cleanupMenuContributionImages(MenuContributionReport report) {
		menuContributionReportImageFileRepository.findByMenuContributionReportIdIn(List.of(report.getId()))
			.forEach(img -> safeDelete(img.getAwsKey()));
	}

	private void moveImagesToMenu(Menu menu, List<MenuContributionReportImageFile> sources) {
		if (sources.isEmpty()) {
			return;
		}
		int order = 0;
		List<MenuImageFile> dest = new ArrayList<>();
		for (MenuContributionReportImageFile src : sources) {
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

	private StoreReportType toStoreReportType(String requestType) {
		try {
			return StoreReportType.valueOf(requestType);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_KIND);
		}
	}

	private Page<AdminReportResponse> getStoreContributionReports(
			ContributionReportType reportType,
			ContributionReportStatus status,
			int page,
			int size) {
		Page<ContributionReport> reports = getContributionReports(
			ContributionTargetType.STORE, reportType, status, page, size);
		List<Long> contributionReportIds = reports.getContent().stream().map(ContributionReport::getId).toList();
		Map<Long, StoreContributionReport> contributionMap = storeContributionReportRepository
			.findByContributionReportIdIn(contributionReportIds).stream()
			.collect(Collectors.toMap(
				contribution -> contribution.getContributionReport().getId(),
				contribution -> contribution));
		List<Long> storeContributionReportIds = contributionMap.values().stream()
			.map(StoreContributionReport::getId)
			.toList();
		Map<Long, List<StoreContributionReportImageFile>> imageMap = storeContributionReportImageFileRepository
			.findByStoreContributionReportIdIn(storeContributionReportIds).stream()
			.collect(Collectors.groupingBy(img -> img.getStoreContributionReport().getId()));

		return reports.map(report -> {
			StoreContributionReport contribution = contributionMap.get(report.getId());
			if (contribution == null) {
				throw new AdminException(AdminErrorCode.REPORT_NOT_FOUND);
			}
			return AdminReportResponse.ofStoreContributionReport(report, contribution,
				imageMap.getOrDefault(contribution.getId(), List.of()));
		});
	}

	private Page<AdminReportResponse> getMenuContributionReports(
			ContributionReportType reportType,
			ContributionReportStatus status,
			int page,
			int size) {
		Page<ContributionReport> reports = getContributionReports(
			ContributionTargetType.MENU, reportType, status, page, size);
		List<Long> contributionReportIds = reports.getContent().stream().map(ContributionReport::getId).toList();
		Map<Long, MenuContributionReport> contributionMap = menuContributionReportRepository
			.findByContributionReportIdIn(contributionReportIds).stream()
			.collect(Collectors.toMap(
				contribution -> contribution.getContributionReport().getId(),
				contribution -> contribution));
		List<Long> menuContributionReportIds = contributionMap.values().stream()
			.map(MenuContributionReport::getId)
			.toList();
		Map<Long, List<MenuContributionReportImageFile>> imageMap = menuContributionReportImageFileRepository
			.findByMenuContributionReportIdIn(menuContributionReportIds).stream()
			.collect(Collectors.groupingBy(img -> img.getMenuContributionReport().getId()));

		return reports.map(report -> {
			MenuContributionReport contribution = contributionMap.get(report.getId());
			if (contribution == null) {
				throw new AdminException(AdminErrorCode.REPORT_NOT_FOUND);
			}
			return AdminReportResponse.ofMenuContributionReport(report, contribution,
				imageMap.getOrDefault(contribution.getId(), List.of()));
		});
	}

	private Page<ContributionReport> getContributionReports(
			ContributionTargetType targetType,
			ContributionReportType reportType,
			ContributionReportStatus status,
			int page,
			int size) {
		PageRequest pageRequest = PageRequest.of(page, size);
		if (status != null) {
			return contributionReportRepository.findByTargetTypeAndReportTypeAndStatus(
				targetType, reportType, status, pageRequest);
		}
		return contributionReportRepository.findByTargetTypeAndReportType(targetType, reportType, pageRequest);
	}

	private Page<AdminReportResponse> getAbuseReports(AbuseReportStatus status, int page, int size) {
		Page<AbuseReport> reports = status != null
			? abuseReportRepository.findByStatus(status, PageRequest.of(page, size))
			: abuseReportRepository.findAll(PageRequest.of(page, size));

		return reports.map(AdminReportResponse::ofAbuseReport);
	}

	private Object toAbuseReportTarget(AbuseReport report) {
		if (report.getTargetType() == AbuseReportTargetType.REVIEW) {
			Review review = reviewRepository.findByIdWithDetails(report.getTargetId())
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			List<String> images = reviewImageFileRepository.findByReviewIdOrderByDisplayOrderAscIdAsc(review.getId())
				.stream()
				.map(ReviewImageFile::getAwsKey)
				.toList();
			return AdminAbuseReportDetailResponse.ReviewTarget.of(review, images);
		}
		return AdminAbuseReportDetailResponse.MemberProfileTarget.from(report.getReportedMember());
	}

	private PriorityAggregation aggregatePriorityMetrics(LocalDateTime since, AbuseReportStatus status) {
		Map<TargetKey, Long> reviewReportCounts24h = toTargetCountMap(
			abuseReportRepository.findTargetReporterCountsSince(AbuseReportTargetType.REVIEW, since, status));
		Map<TargetKey, Long> sensitiveReasonReportCounts = toTargetCountMap(
			abuseReportRepository.findSensitiveReasonReporterCounts(SENSITIVE_REASON_TYPES, status));
		Map<Long, Long> memberReviewCounts24h = abuseReportRepository
			.findMemberReviewTargetCountsSince(AbuseReportTargetType.REVIEW, since, status).stream()
			.collect(Collectors.toMap(
				projection -> projection.getReportedMemberId(),
				projection -> nullToZero(projection.getReviewTargetCount())));

		return new PriorityAggregation(
			reviewReportCounts24h,
			sensitiveReasonReportCounts,
			memberReviewCounts24h);
	}

	private Map<TargetKey, Long> toTargetCountMap(
			List<? extends AbuseReportTargetCountProjection> counts) {
		Map<TargetKey, Long> result = new HashMap<>();
		for (var count : counts) {
			result.put(new TargetKey(count.getTargetType(), count.getTargetId()), nullToZero(count.getReporterCount()));
		}
		return result;
	}

	private AdminHighPriorityReportResponse toHighPriorityReportResponse(
			AbuseReport report,
			PriorityAggregation aggregation) {
		TargetKey targetKey = new TargetKey(report.getTargetType(), report.getTargetId());
		long reviewReportCount24h = aggregation.reviewReportCounts24h().getOrDefault(targetKey, 0L);
		long sensitiveReasonReportCount = aggregation.sensitiveReasonReportCounts().getOrDefault(targetKey, 0L);
		long memberReviewReportCount24h = aggregation.memberReviewCounts24h()
			.getOrDefault(report.getReportedMember().getId(), 0L);
		boolean temporaryHideCandidate = report.getTargetType() == AbuseReportTargetType.REVIEW
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

	private void applyReviewModeration(AbuseReport report, AbuseReportAction action, String reason) {
		if (action == AbuseReportAction.NONE) {
			return;
		}
		if (!isReviewModerationAction(action)) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
		}
		if (report.getTargetType() != AbuseReportTargetType.REVIEW) {
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
				if (report.getModerationAction() == AbuseReportAction.DELETE_REVIEW) {
					review.restoreModeration();
				} else {
					review.restoreHidden();
				}
			}
			default -> throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
		}
	}

	private boolean isReviewModerationAction(AbuseReportAction action) {
		return action == AbuseReportAction.HIDE_REVIEW
			|| action == AbuseReportAction.DELETE_REVIEW
			|| action == AbuseReportAction.RESTORE_REVIEW;
	}

	private boolean shouldNotifyModeratedMember(AbuseReportAction action) {
		return action == AbuseReportAction.HIDE_REVIEW
			|| action == AbuseReportAction.DELETE_REVIEW;
	}

	private void assertContributionPending(ContributionReport contributionReport, StoreReportStatus sourceStatus) {
		if (contributionReport.getStatus() != ContributionReportStatus.PENDING
			|| sourceStatus != StoreReportStatus.PENDING) {
			throw new AdminException(AdminErrorCode.REPORT_ALREADY_REVIEWED);
		}
	}

	private AbuseReportAction toModerationAction(String action) {
		if (action == null || action.isBlank()) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
		}
		try {
			AbuseReportAction parsed = AbuseReportAction.valueOf(action.trim().toUpperCase(Locale.ROOT));
			if (parsed == AbuseReportAction.NONE || isReviewModerationAction(parsed)) {
				return parsed;
			}
		} catch (IllegalArgumentException e) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
		}
		throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
	}

	private ContributionReportStatus toContributionReportStatus(String statusStr) {
		if (statusStr == null) {
			return null;
		}
		try {
			return ContributionReportStatus.valueOf(statusStr.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_ACTION);
		}
	}

	private AbuseReportStatus toAbuseReportStatus(String statusStr) {
		if (statusStr == null) {
			return null;
		}
		try {
			return AbuseReportStatus.valueOf(statusStr.toUpperCase());
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

	private record TargetKey(AbuseReportTargetType targetType, Long targetId) {
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
