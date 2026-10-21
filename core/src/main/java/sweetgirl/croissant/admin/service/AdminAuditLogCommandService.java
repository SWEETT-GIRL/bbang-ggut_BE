package sweetgirl.croissant.admin.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.entity.AdminAuditLog;
import sweetgirl.croissant.admin.repository.AdminAuditLogRepository;

@Service
@RequiredArgsConstructor
public class AdminAuditLogCommandService {

	private final AdminAuditLogRepository adminAuditLogRepository;

	/** 어드민 요청과 따로 커밋한다 — 감사 로그 저장이 실패해도 어드민 요청 결과는 바뀌지 않는다(실패 처리는 필터가 한다). */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(AdminAuditLog auditLog) {
		adminAuditLogRepository.save(auditLog);
	}

	@Transactional
	public int deleteCreatedBefore(Instant cutoff) {
		return adminAuditLogRepository.deleteCreatedBefore(cutoff);
	}
}
