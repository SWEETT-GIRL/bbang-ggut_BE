package sweetgirl.croissant.course.draft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.global.response.ApiResponse;

/**
 * 과부하 보호 — 동시에 처리하는 초안 PUT 수에 상한을 두고, 넘치면 기다리게 하지 않고 바로 429 로 돌려보낸다.
 *
 * <p>왜: 한계를 넘는 부하가 오면 요청이 전부 스레드 풀 앞에 줄을 서서 <b>모두 느려진다</b>(편집자 2,500명에서 p95 1.8초,
 * 2026-11-10). 받을 수 있는 만큼만 받고 나머지를 즉시 거절하면 받은 요청은 평소 속도로 끝난다.
 *
 * <p><b>503 이 아니라 429 다.</b> 앱은 503(COURSE503_1)을 받으면 그 글을 PATCH 로 보내고 이후 PATCH 만 쓴다 — 타자마다 DB 를
 * 쓰는 더 무거운 길이라, 바쁠 때 그리로 몰면 과부하가 커진다. 429(COURSE429_1)는 0.5초 뒤 같은 글을 다시 보낸다. 이미 배포된
 * 앱이 아는 응답이고, 다시 보내는 글이 가장 최신 글이라 잃는 것이 없다.
 *
 * <p><b>기본은 꺼 둔다({@code course.draft.max-concurrent-writes=0}).</b> 지금 앱은 429 를 받으면 0.5초 뒤 무조건 다시 보내서,
 * 거절이 곧 재요청이 된다 — 편집자 4,000명에서 받은 요청의 p95 는 1.9초 → 0.27초로 줄지만 초당 성공은 2,634 → 1,915 로
 * 오히려 준다(거절 한 건도 정상 처리의 1/3 만큼 CPU 를 쓴다). 거절당한 쪽이 2초쯤 쉬면 2,485 건 · p95 0.11초가 된다
 * (2026-11-10, r7g.xlarge). 앱이 429 에 길게 쉬도록 바뀐 뒤에 켠다.
 *
 * <p>Security 체인보다 앞에 둔다 — 거절된 요청은 JWT 검증 · 권한 판정 · Redis 를 타지 않아 거절 자체가 싸다. 그래서 회원을
 * 모르고, 회원별 한도({@link MemoDraftRateLimiter})와는 별개로 인스턴스 전체를 지킨다.
 */
public class MemoDraftLoadShedFilter extends OncePerRequestFilter {

	private static final Pattern MEMO_DRAFT_PATH = Pattern.compile("^/courses/\\d+/stores/\\d+/memo-draft$");

	private final Semaphore permits;
	private final Counter shed;
	private final byte[] tooBusyBody;

	public MemoDraftLoadShedFilter(int maxConcurrentWrites, ObjectMapper objectMapper, MeterRegistry registry) {
		this.permits = new Semaphore(maxConcurrentWrites);
		this.shed = Counter.builder("draft_write_shed_total").description("과부하 보호로 바로 거절한 초안 PUT 수")
			.register(registry);
		Gauge.builder("draft_write_in_flight", permits, semaphore -> maxConcurrentWrites - semaphore.availablePermits())
			.description("지금 처리 중인 초안 PUT 수").register(registry);
		this.tooBusyBody = serialize(objectMapper);
	}

	private static byte[] serialize(ObjectMapper objectMapper) {
		try {
			return objectMapper.writeValueAsString(ApiResponse.fail(CourseErrorCode.COURSE_DRAFT_TOO_FREQUENT))
				.getBytes(StandardCharsets.UTF_8);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("429 응답 바디를 만들 수 없습니다.", exception);
		}
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return !"PUT".equals(request.getMethod()) || !MEMO_DRAFT_PATH.matcher(path).matches();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		if (!permits.tryAcquire()) {
			shed.increment();
			writeTooBusy(response);
			return;
		}
		try {
			filterChain.doFilter(request, response);
		} finally {
			permits.release();
		}
	}

	private void writeTooBusy(HttpServletResponse response) throws IOException {
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setHeader("Retry-After", "1");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentLength(tooBusyBody.length);
		response.getOutputStream().write(tooBusyBody);
	}
}
