package sweetgirl.croissant.course.signal;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.course.draft.CourseDraftConfig;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.service.CourseAccessPolicy;
import sweetgirl.croissant.member.jwt.JwtProvider;

/**
 * STOMP 프레임의 인증·인가. 핸드셰이크(/ws)는 열어 두고 CONNECT 헤더의 {@code Authorization: Bearer} 로 회원을 확인한다 —
 * React Native 의 WebSocket 은 핸드셰이크에 헤더를 못 붙인다. SUBSCRIBE 는 {@code /user/queue/courses/{courseId}} 만 받고,
 * 그 코스를 볼 수 있는지(canView) 그 자리에서 판정한다. 연결 중 토큰이 만료돼도 끊지 않는다 — 끊기면 앱이 새 토큰으로 다시 붙고,
 * 접근 권한 변화(나가기·삭제)는 REVOKE 신호가 구독 명단에서 빼서 맡는다.
 *
 * <p>거절은 예외로 던진다 → 클라이언트에 ERROR 프레임이 가고 연결이 닫힌다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = CourseDraftConfig.REDIS_MODE_PROPERTY)
public class StompAuthInterceptor implements ChannelInterceptor {

	static final String DESTINATION_PREFIX = "/user/queue/courses/";
	private static final Pattern DESTINATION = Pattern.compile("^/user/queue/courses/(\\d+)$");
	private static final String BEARER = "Bearer ";

	private final JwtProvider jwtProvider;
	private final CourseRepository courseRepository;
	private final CourseAccessPolicy courseAccessPolicy;
	private final CourseSubscriptionRegistry registry;

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (accessor == null || accessor.getCommand() == null) {
			return message;
		}
		switch (accessor.getCommand()) {
			case CONNECT -> authenticate(message, accessor);
			case SUBSCRIBE -> authorizeSubscribe(message, accessor);
			case UNSUBSCRIBE -> registry.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId());
			case DISCONNECT -> registry.disconnect(accessor.getSessionId());
			default -> {
				// SEND·ACK 등은 받지 않는 서비스다 — 브로커가 알아서 버린다
			}
		}
		return message;
	}

	private void authenticate(Message<?> message, StompHeaderAccessor accessor) {
		String authorization = accessor.getFirstNativeHeader("Authorization");
		if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER)) {
			throw new MessageDeliveryException(message, "CONNECT 에 Authorization: Bearer 헤더가 없다");
		}
		String token = authorization.substring(BEARER.length());
		if (!jwtProvider.validateToken(token)) {
			throw new MessageDeliveryException(message, "토큰이 유효하지 않다");
		}
		Long memberId = jwtProvider.getMemberId(token);
		accessor.setUser(new UsernamePasswordAuthenticationToken(memberId, null, List.of()));
	}

	private void authorizeSubscribe(Message<?> message, StompHeaderAccessor accessor) {
		Long memberId = memberIdOf(message, accessor);
		String destination = accessor.getDestination();
		Matcher matcher = destination == null ? null : DESTINATION.matcher(destination);
		if (matcher == null || !matcher.matches()) {
			throw new MessageDeliveryException(message, "구독할 수 없는 목적지다: " + destination);
		}
		Long courseId = Long.parseLong(matcher.group(1));
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new MessageDeliveryException(message, "코스가 없다: " + courseId));
		if (!courseAccessPolicy.canView(course, memberId, null)) {
			throw new MessageDeliveryException(message, "볼 수 없는 코스다: " + courseId);
		}
		registry.subscribe(courseId, memberId, accessor.getSessionId(), accessor.getSubscriptionId());
	}

	private Long memberIdOf(Message<?> message, StompHeaderAccessor accessor) {
		if (accessor.getUser() instanceof UsernamePasswordAuthenticationToken authentication
			&& authentication.getPrincipal() instanceof Long memberId) {
			return memberId;
		}
		throw new MessageDeliveryException(message, "인증되지 않은 세션이다");
	}

	/** 앱이 구독하는 목적지. 신호는 이 세션에만 간다. */
	static String destinationOf(Long courseId) {
		return DESTINATION_PREFIX + courseId;
	}

	static StompCommand commandOf(Message<?> message) {
		StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		return accessor == null ? null : accessor.getCommand();
	}
}
