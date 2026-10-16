package sweetgirl.croissant.admin.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.response.AdminAuditLogResponse;
import sweetgirl.croissant.admin.repository.AdminAuditLogRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAuditLogQueryService {

	static final int MAX_SIZE = 100;

	private final AdminAuditLogRepository adminAuditLogRepository;

	/** 최신순. size 는 1~100 으로 자른다. 대상 종류는 대문자로 맞춘다(MEMBER · COURSE ...). */
	public AdminAuditLogResponse.PageResponse search(String targetType, String targetId, int page, int size) {
		PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE),
			Sort.by(Sort.Direction.DESC, "id"));
		String type = StringUtils.hasText(targetType) ? targetType.strip().toUpperCase() : null;
		String id = StringUtils.hasText(targetId) ? targetId.strip() : null;
		return AdminAuditLogResponse.PageResponse.from(adminAuditLogRepository.search(type, id, pageable));
	}
}
