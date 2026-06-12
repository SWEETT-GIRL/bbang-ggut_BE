package sweetgirl.croissant.store.service;

import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.naver.client.NaverLocalSearchClient;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.code.StoreReportErrorCode;
import sweetgirl.croissant.store.dto.request.StoreAddReportRequest;
import sweetgirl.croissant.store.dto.response.NaverPlaceSearchResult;
import sweetgirl.croissant.store.dto.response.StoreAddReportResponse;
import sweetgirl.croissant.store.entity.StoreAddReport;
import sweetgirl.croissant.store.enums.StoreReportStatus;
import sweetgirl.croissant.store.exception.StoreReportException;
import sweetgirl.croissant.store.repository.StoreAddReportRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
public class StoreAddReportService {

	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

	private final NaverLocalSearchClient naverLocalSearchClient;
	private final MemberReader memberReader;
	private final StoreRepository storeRepository;
	private final StoreAddReportRepository storeAddReportRepository;

	public List<NaverPlaceSearchResult> searchNaverPlaces(String keyword) {
		return naverLocalSearchClient.search(keyword);
	}

	@Transactional
	public StoreAddReportResponse createReport(Long memberId, StoreAddReportRequest request) {
		Member member = memberId != null ? memberReader.getMember(memberId) : null;

		if (request.naverPlaceId() != null && !request.naverPlaceId().isBlank()) {
			if (storeRepository.findByNaverPlaceId(request.naverPlaceId()).isPresent()) {
				throw new StoreReportException(StoreReportErrorCode.REPORT_STORE_ALREADY_EXISTS);
			}
			if (storeAddReportRepository.existsByNaverPlaceIdAndStatus(
				request.naverPlaceId(), StoreReportStatus.PENDING)) {
				throw new StoreReportException(StoreReportErrorCode.REPORT_DUPLICATED);
			}
		}
		if (storeAddReportRepository.existsPendingByNameAndAddress(
			request.name(), request.address(), StoreReportStatus.PENDING)) {
			throw new StoreReportException(StoreReportErrorCode.REPORT_DUPLICATED);
		}

		StoreAddReport report = storeAddReportRepository.save(StoreAddReport.builder()
			.member(member)
			.naverPlaceId(blankToNull(request.naverPlaceId()))
			.name(request.name())
			.address(request.address())
			.roadAddress(request.roadAddress())
			.phone(request.phone())
			.location(toPoint(request.latitude(), request.longitude()))
			.reason(request.reason())
			.build());

		return new StoreAddReportResponse(report.getId(), report.getStatus());
	}

	private Point toPoint(double latitude, double longitude) {
		return GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
	}

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
