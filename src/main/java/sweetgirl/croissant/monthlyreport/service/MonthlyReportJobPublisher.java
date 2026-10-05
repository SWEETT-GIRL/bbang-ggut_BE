package sweetgirl.croissant.monthlyreport.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportJob;

/** 실패한 발행 트랜잭션을 먼저 롤백한 뒤 작업 트랜잭션이 재시도 상태를 저장할 수 있게 분리한다. */
@Service
@RequiredArgsConstructor
public class MonthlyReportJobPublisher {
	private final MonthlyReportPayloadCodec codec;
	private final ObjectMapper mapper;
	private final MonthlyReportGenerationService generation;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean publish(MonthlyReportJob job) {
		try {
			JsonNode metrics = mapper.readTree(job.getRawMetrics());
			if (!(metrics instanceof ObjectNode object)) {
				throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
			}
			return generation.publishFromDraft(codec.read(job.getDraft(), job.getId().getMemberId(),
				job.getId().getYearMonth()), object, job.getCalculationVersion());
		} catch (JsonProcessingException e) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT, e);
		}
	}
}
