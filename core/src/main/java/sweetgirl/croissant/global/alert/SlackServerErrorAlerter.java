package sweetgirl.croissant.global.alert;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import jakarta.servlet.http.HttpServletRequest;
import sweetgirl.croissant.external.slack.client.SlackWebhookClient;
import sweetgirl.croissant.external.slack.config.SlackProperties;
import sweetgirl.croissant.external.slack.dto.SlackMessage;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.global.logging.SensitiveUrlMasker;
import sweetgirl.croissant.global.logging.filter.RequestIdFilter;

/**
 * 5xx 를 슬랙 장애 채널로 짧게 보낸다 — 메서드·경로·상태·예외 클래스와 메시지·요청 id·회원 id.
 *
 * <p>같은 오류(예외 클래스 + 처음 터진 자리)는 {@code cooldown}(기본 5분)에 한 번만 보낸다. DB 가 멈추면 요청마다
 * 같은 예외가 나는데, 그걸 다 보내면 채널이 잠기고 진짜 새 오류가 묻힌다. 기억은 메모리(Caffeine)에만 둔다 — 서버가
 * 다시 뜨면 한 번 더 오는 정도는 괜찮다.
 *
 * <p>전송은 요청 스레드가 기다리지 않게 {@code logExecutor}(넘치면 버림)에 맡긴다. 웹훅 주소가 비어 있으면 맡지 않고
 * false 를 돌려 기존 로그 어펜더가 그대로 보내게 둔다.
 *
 * <p>{@code ops.alert.server-error.enabled=true} 인 곳(운영)에서만 빈이 생긴다.
 */
@Component
@ConditionalOnProperty(name = "ops.alert.server-error.enabled", havingValue = "true")
public class SlackServerErrorAlerter implements ServerErrorAlerter {

	private static final int ERROR_COLOR = 0xE74C3C;
	private static final int MAX_MESSAGE_LENGTH = 500;
	private static final String NONE = "-";

	private final SlackWebhookClient slackWebhookClient;
	private final SlackProperties slackProperties;
	private final Executor executor;
	private final String webhookUrl;
	private final Cache<String, Boolean> recentlySent;

	public SlackServerErrorAlerter(
			SlackWebhookClient slackWebhookClient,
			SlackProperties slackProperties,
			@Qualifier("logExecutor") Executor executor,
			@Value("${ops.alert.server-error.webhook-url:}") String webhookUrl,
			@Value("${ops.alert.server-error.cooldown:5m}") Duration cooldown) {
		this.slackWebhookClient = slackWebhookClient;
		this.slackProperties = slackProperties;
		this.executor = executor;
		this.webhookUrl = webhookUrl;
		this.recentlySent = Caffeine.newBuilder()
			.expireAfterWrite(cooldown)
			.maximumSize(1_000)
			.build();
	}

	@Override
	public boolean alert(HttpServletRequest request, int status, Throwable exception) {
		if (webhookUrl == null || webhookUrl.isBlank()) {
			return false;
		}
		if (recentlySent.asMap().putIfAbsent(signature(exception), Boolean.TRUE) != null) {
			return true;
		}
		SlackMessage message = toMessage(request, status, exception);
		executor.execute(() -> slackWebhookClient.send(webhookUrl, message));
		return true;
	}

	/** 같은 오류인지 가르는 열쇠. 메시지는 id·값이 섞여 매번 달라지므로 쓰지 않는다. */
	static String signature(Throwable exception) {
		if (exception instanceof CustomException customException) {
			return exception.getClass().getName() + "#" + customException.getErrorCode().getCode();
		}
		StackTraceElement[] stackTrace = exception.getStackTrace();
		String origin = stackTrace.length > 0 ? stackTrace[0].toString() : NONE;
		return exception.getClass().getName() + "@" + origin;
	}

	SlackMessage toMessage(HttpServletRequest request, int status, Throwable exception) {
		String path = SensitiveUrlMasker.maskPath(request.getRequestURI());
		String title = status + " " + request.getMethod() + " " + path;
		String description = "*" + exception.getClass().getSimpleName() + "*: " + truncate(exception.getMessage());
		List<SlackMessage.Field> fields = List.of(
			new SlackMessage.Field("requestId", valueOrNone(MDC.get(RequestIdFilter.MDC_REQUEST_ID)), true),
			new SlackMessage.Field("memberId", resolveMemberId(), true));
		return new SlackMessage(ERROR_COLOR, withPrefix("🔴 " + title), description, Instant.now(), fields);
	}

	private String withPrefix(String title) {
		String prefix = slackProperties.getTitlePrefix();
		if (prefix == null || prefix.isBlank()) {
			return title;
		}
		return prefix.strip() + " " + title;
	}

	private String resolveMemberId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof Long memberId) {
			return String.valueOf(memberId);
		}
		return NONE;
	}

	private static String truncate(String message) {
		if (message == null || message.isBlank()) {
			return NONE;
		}
		String singleLine = message.replaceAll("[\\r\\n\\t]+", " ");
		if (singleLine.length() <= MAX_MESSAGE_LENGTH) {
			return singleLine;
		}
		return singleLine.substring(0, MAX_MESSAGE_LENGTH) + "...";
	}

	private static String valueOrNone(String value) {
		return value == null || value.isBlank() ? NONE : value;
	}
}
