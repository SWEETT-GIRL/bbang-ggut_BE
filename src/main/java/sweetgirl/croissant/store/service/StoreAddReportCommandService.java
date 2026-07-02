package sweetgirl.croissant.store.service;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.contribution.entity.StoreContributionReport;
import sweetgirl.croissant.contribution.enums.ContributionReportType;
import sweetgirl.croissant.contribution.enums.ContributionTargetType;
import sweetgirl.croissant.contribution.repository.ContributionReportRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.notification.service.AdminReportNotificationPublisher;
import sweetgirl.croissant.store.code.StoreReportErrorCode;
import sweetgirl.croissant.store.dto.request.StoreAddReportRequest;
import sweetgirl.croissant.store.dto.response.StoreAddReportResponse;
import sweetgirl.croissant.store.enums.StoreReportStatus;
import sweetgirl.croissant.store.exception.StoreReportException;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class StoreAddReportCommandService {

	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

	private final MemberReader memberReader;
	private final StoreRepository storeRepository;
	private final ContributionReportRepository contributionReportRepository;
	private final StoreContributionReportRepository storeContributionReportRepository;
	private final AdminReportNotificationPublisher adminReportNotificationPublisher;

	public StoreAddReportResponse createReport(Long memberId, StoreAddReportRequest request) {
		Member member = memberId != null ? memberReader.getMember(memberId) : null;

		if (request.naverPlaceId() != null && !request.naverPlaceId().isBlank()) {
			if (storeRepository.findByNaverPlaceId(request.naverPlaceId()).isPresent()) {
				throw new StoreReportException(StoreReportErrorCode.REPORT_STORE_ALREADY_EXISTS);
			}
			if (storeContributionReportRepository.existsByNaverPlaceIdAndStatus(
				request.naverPlaceId(), StoreReportStatus.PENDING)) {
				throw new StoreReportException(StoreReportErrorCode.REPORT_DUPLICATED);
			}
		}
		if (storeContributionReportRepository.existsPendingByNameAndAddress(
			request.name(), request.address(), StoreReportStatus.PENDING)) {
			throw new StoreReportException(StoreReportErrorCode.REPORT_DUPLICATED);
		}

		ContributionReport contributionReport = contributionReportRepository.save(ContributionReport.builder()
			.member(member)
			.targetType(ContributionTargetType.STORE)
			.reportType(ContributionReportType.ADD)
			.build());
		StoreContributionReport report = storeContributionReportRepository.save(StoreContributionReport.builder()
			.contributionReport(contributionReport)
			.requestType("STORE_ADD")
			.naverPlaceId(blankToNull(request.naverPlaceId()))
			.name(request.name())
			.address(request.address())
			.roadAddress(request.roadAddress())
			.phone(request.phone())
			.location(toPoint(request.latitude(), request.longitude()))
			.reason(request.reason())
			.build());

		adminReportNotificationPublisher.publishStoreContributionReportCreated(report);
		return new StoreAddReportResponse(contributionReport.getId(), report.getStatus());
	}

	private Point toPoint(double latitude, double longitude) {
		return GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
	}

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

}
