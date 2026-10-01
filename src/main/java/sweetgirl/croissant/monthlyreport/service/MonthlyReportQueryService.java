package sweetgirl.croissant.monthlyreport.service;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyReportQueryService {

	private final MemberReader memberReader;
	private final MonthlyReportRepository repository;
	private final MonthlyReportPayloadCodec codec;

	public MonthlyReportResponse getReport(Long memberId, String yearMonth) {
		memberReader.getMember(memberId);
		MonthlyReportMonth.parse(yearMonth);
		var snapshot = repository.find(memberId, yearMonth)
			.orElseThrow(() -> new CustomException(MonthlyReportErrorCode.NOT_PUBLISHED));
		switch (snapshot.status()) {
			case "WITHHELD" -> throw new CustomException(MonthlyReportErrorCode.NOT_PUBLISHED);
			case "FAILED" -> throw new CustomException(MonthlyReportErrorCode.GENERATION_FAILED);
			case "PUBLISHED" -> {
				MonthlyReportResponse report = codec.read(snapshot.payload(), memberId, yearMonth);
				return removeUnavailablePhotos(report);
			}
			default -> throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
		}
	}

	private MonthlyReportResponse removeUnavailablePhotos(MonthlyReportResponse report) {
		Set<String> keys = report.breads().stream().map(MonthlyReportResponse.Bread::imageKey)
			.filter(Objects::nonNull).collect(Collectors.toSet());
		if (keys.isEmpty()) {
			return report;
		}
		Set<String> visible = repository.findVisiblePhotoKeys(report.memberId(), keys);
		return report.withBreads(report.breads().stream()
			.map(bread -> new MonthlyReportResponse.Bread(bread.name(), bread.count(),
				bread.imageKey() != null && visible.contains(bread.imageKey()) ? bread.imageKey() : null))
			.toList());
	}
}
