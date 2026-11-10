package sweetgirl.croissant.global.logging.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.global.logging.entity.RequestLog;

@ExtendWith(MockitoExtension.class)
class RequestLogServiceTest {

	@Mock
	private RequestLogWriter requestLogWriter;

	@InjectMocks
	private RequestLogService requestLogService;

	private static RequestLog log() {
		return RequestLog.builder().httpMethod("GET").endpoint("/stores/1090").responseStatus(200).build();
	}

	@Test
	@DisplayName("저장은 DB 에 쓰지 않고 대기열에 넣는다")
	void save_enqueues() {
		RequestLog requestLog = log();

		requestLogService.save(requestLog);

		verify(requestLogWriter).enqueue(requestLog);
	}

	@Test
	@DisplayName("대기열이 가득 차 버려져도 요청 쪽에는 예외가 나지 않는다")
	void save_whenQueueFull_doesNotThrow() {
		RequestLog requestLog = log();
		when(requestLogWriter.enqueue(requestLog)).thenReturn(false);

		assertThatCode(() -> requestLogService.save(requestLog)).doesNotThrowAnyException();
	}
}
