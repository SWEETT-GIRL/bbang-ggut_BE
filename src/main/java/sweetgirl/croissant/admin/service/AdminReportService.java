package sweetgirl.croissant.admin.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.dto.request.AdminReportReviewRequest;
import sweetgirl.croissant.admin.dto.response.AdminReportResponse;
import sweetgirl.croissant.admin.exception.AdminException;
import sweetgirl.croissant.menu.entity.MenuAddReport;
import sweetgirl.croissant.menu.entity.MenuAddReportImageFile;
import sweetgirl.croissant.menu.repository.MenuAddReportImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuAddReportRepository;
import sweetgirl.croissant.store.entity.StoreReport;
import sweetgirl.croissant.store.entity.StoreReportImageFile;
import sweetgirl.croissant.store.enums.StoreReportStatus;
import sweetgirl.croissant.store.repository.StoreReportImageFileRepository;
import sweetgirl.croissant.store.repository.StoreReportRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminReportService {

	private final StoreReportRepository storeReportRepository;
	private final MenuAddReportRepository menuAddReportRepository;
	private final StoreReportImageFileRepository storeReportImageFileRepository;
	private final MenuAddReportImageFileRepository menuAddReportImageFileRepository;

	public Page<AdminReportResponse> getReports(String kind, String statusStr, int page, int size) {
		StoreReportStatus status = statusStr != null ? toStatus(statusStr) : null;

		if ("MENU_ADD_REPORT".equals(kind)) {
			return getMenuAddReports(status, page, size);
		}
		return getStoreReports(status, page, size);
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
				report.approve();
			} else {
				report.reject();
			}
		} else if ("STORE_REPORT".equals(request.kind())) {
			StoreReport report = storeReportRepository.findById(reportId)
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
			if (report.getStatus() != StoreReportStatus.PENDING) {
				throw new AdminException(AdminErrorCode.REPORT_ALREADY_REVIEWED);
			}
			if (accept) {
				report.approve();
			} else {
				report.reject();
			}
		} else {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_KIND);
		}
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

	private StoreReportStatus toStatus(String statusStr) {
		try {
			return StoreReportStatus.valueOf(statusStr.toUpperCase());
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
}
