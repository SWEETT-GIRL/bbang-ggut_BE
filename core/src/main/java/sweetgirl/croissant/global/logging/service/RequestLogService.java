package sweetgirl.croissant.global.logging.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.logging.entity.RequestLog;
import sweetgirl.croissant.global.logging.repository.RequestLogRepository;

@Service
@RequiredArgsConstructor
public class RequestLogService {

	private final RequestLogRepository requestLogRepository;

	@Async("logExecutor")
	public void save(RequestLog log) {
		requestLogRepository.save(log);
	}
}
