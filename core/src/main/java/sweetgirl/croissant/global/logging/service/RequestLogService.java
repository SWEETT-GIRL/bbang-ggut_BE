package sweetgirl.croissant.global.logging.service;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.logging.entity.RequestLog;

@Service
@RequiredArgsConstructor
public class RequestLogService {

	private final RequestLogWriter requestLogWriter;

	/** 대기열에 넣고 바로 돌아온다 — 실제 INSERT 는 {@link RequestLogWriter} 가 0.5초마다 묶어서 한다. */
	public void save(RequestLog log) {
		requestLogWriter.enqueue(log);
	}
}
