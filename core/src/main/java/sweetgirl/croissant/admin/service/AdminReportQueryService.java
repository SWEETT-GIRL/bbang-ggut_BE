package sweetgirl.croissant.admin.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.enums.AbuseReportStatus;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;
import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.dto.response.AdminAbuseReportDetailResponse;
import sweetgirl.croissant.admin.dto.response.AdminAbuseReportStatsResponse;
import sweetgirl.croissant.admin.dto.response.AdminHighPriorityReportResponse;
import sweetgirl.croissant.admin.dto.response.AdminReportDetailResponse;
import sweetgirl.croissant.admin.dto.response.AdminReportResponse;
import sweetgirl.croissant.admin.exception.AdminException;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.comment.entity.BreadRecordComment;
import sweetgirl.croissant.comment.repository.BreadRecordCommentRepository;
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
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.entity.ReviewImageFile;
import sweetgirl.croissant.review.repository.ReviewImageFileRepository;
import sweetgirl.croissant.review.repository.ReviewRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminReportQueryService {

	private final ContributionReportRepository contributionReportRepository;
	private final StoreContributionReportRepository storeContributionReportRepository;
	private final MenuContributionReportRepository menuContributionReportRepository;
	private final AbuseReportRepository abuseReportRepository;
	private final StoreContributionReportImageFileRepository storeContributionReportImageFileRepository;
	private final MenuContributionReportImageFileRepository menuContributionReportImageFileRepository;
	private final ReviewRepository reviewRepository;
	private final ReviewImageFileRepository reviewImageFileRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final BreadRecordPhotoRepository breadRecordPhotoRepository;
	private final BreadRecordCommentRepository commentRepository;
	private final AbuseReportPriorityAnalyzer priorityAnalyzer;

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
		return priorityAnalyzer.getAbuseReportStats();
	}

	public Page<AdminHighPriorityReportResponse> getHighPriorityReports(String statusStr, int page, int size) {
		return priorityAnalyzer.getHighPriorityReports(toAbuseReportStatus(statusStr), page, size);
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
		if (report.getTargetType() == AbuseReportTargetType.RECORD) {
			BreadRecord record = breadRecordRepository.findById(report.getTargetId())
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			List<String> photos = breadRecordPhotoRepository
				.findByRecordIdInOrderByRecordIdAscSortOrderAscIdAsc(List.of(record.getId())).stream()
				.map(BreadRecordPhoto::getAwsKey)
				.toList();
			return AdminAbuseReportDetailResponse.RecordTarget.of(record, photos);
		}
		if (report.getTargetType() == AbuseReportTargetType.COMMENT) {
			BreadRecordComment comment = commentRepository.findById(report.getTargetId())
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			return AdminAbuseReportDetailResponse.CommentTarget.from(comment);
		}
		return AdminAbuseReportDetailResponse.MemberProfileTarget.from(report.getReportedMember());
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
}
