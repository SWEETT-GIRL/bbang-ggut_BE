package sweetgirl.croissant.abuse.service;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.enums.AbuseReportStatus;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;

/**
 * 신고한 사람 화면에서 그 빵로그 · 댓글 · 프로필을 바로 숨긴다(피그마 08-A4 "접수됐어요" 뒤). 목록 · 상세 · 댓글 조회가 같이 쓴다.
 * 처리 전(PENDING) · 받아들여진(ACCEPTED) 신고만 — 기각되면 다시 보인다.
 */
@Component
@RequiredArgsConstructor
public class ReportedTargetReader {

	static final List<AbuseReportStatus> HIDING_STATUSES = List.of(AbuseReportStatus.PENDING,
		AbuseReportStatus.ACCEPTED);

	private final AbuseReportRepository abuseReportRepository;

	public Set<Long> reportedIds(Long viewerId, AbuseReportTargetType targetType) {
		if (viewerId == null) {
			return Set.of();
		}
		return Set.copyOf(abuseReportRepository.findTargetIdsByReporter(viewerId, targetType, HIDING_STATUSES));
	}

	public boolean hasReported(Long viewerId, AbuseReportTargetType targetType, Long targetId) {
		return viewerId != null && abuseReportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStatusIn(
			viewerId, targetType, targetId, HIDING_STATUSES);
	}

	/** 목록에서 신고한 것을 뺀다. 순서는 그대로. */
	public static <T> List<T> without(Collection<T> rows, java.util.function.Function<T, Long> idOf,
			Set<Long> reportedIds) {
		if (reportedIds.isEmpty()) {
			return List.copyOf(rows);
		}
		return rows.stream().filter(row -> !reportedIds.contains(idOf.apply(row))).toList();
	}
}
