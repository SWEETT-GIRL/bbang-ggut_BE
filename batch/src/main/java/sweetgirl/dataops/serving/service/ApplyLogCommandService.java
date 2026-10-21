package sweetgirl.dataops.serving.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.serving.entity.ApplyLog;
import sweetgirl.dataops.serving.enums.ApplyOrigin;
import sweetgirl.dataops.serving.repository.ApplyLogRepository;

/**
 * apply_log 선(先)기록 — 운영 DML 전에 REQUIRES_NEW 로 즉시 커밋해, 이후 단계가 실패해도
 * 감사 기록은 남긴다 (설계서 4.2 트랜잭션 순서: 선기록 → 운영 커밋 → 상태 갱신, 중간 실패는 대사로).
 */
@Service
@RequiredArgsConstructor
public class ApplyLogCommandService {

	private final ApplyLogRepository applyLogRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Long recordBeforeApply(Long storeId, String target, String oldValue, String newValue,
			ApplyOrigin origin, Long proposalId, Long snapshotId) {
		return applyLogRepository.save(ApplyLog.builder()
			.storeId(storeId)
			.target(target)
			.oldValue(oldValue)
			.newValue(newValue)
			.origin(origin)
			.proposalId(proposalId)
			.snapshotId(snapshotId)
			.build()).getId();
	}
}
