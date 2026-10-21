package sweetgirl.dataops.serving.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.global.response.PageResponse;
import sweetgirl.dataops.serving.dto.response.ApplyLogResponse;
import sweetgirl.dataops.serving.entity.ApplyLog;
import sweetgirl.dataops.serving.repository.ApplyLogRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApplyLogQueryService {

	private final ApplyLogRepository applyLogRepository;

	public PageResponse<ApplyLogResponse> getApplyLogs(Long storeId, int page, int size) {
		Pageable pageable = PageRequest.of(page, size);
		Page<ApplyLog> applyLogs = storeId != null
			? applyLogRepository.findByStoreIdOrderByAppliedAtDesc(storeId, pageable)
			: applyLogRepository.findAllByOrderByAppliedAtDesc(pageable);
		return PageResponse.from(applyLogs.map(ApplyLogResponse::from));
	}
}
