package sweetgirl.croissant.course.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.service.CourseAccessPolicy;
import sweetgirl.croissant.member.jwt.JwtProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("StompAuthInterceptor")
class StompAuthInterceptorTest {

	private static final String SESSION_ID = "s-1";
	private static final Long MEMBER_ID = 7L;
	private static final Long COURSE_ID = 10L;

	@Mock
	private JwtProvider jwtProvider;
	@Mock
	private CourseRepository courseRepository;
	@Mock
	private CourseAccessPolicy courseAccessPolicy;
	@Mock
	private CourseSubscriptionRegistry registry;

	@InjectMocks
	private StompAuthInterceptor interceptor;

	private MessageChannel channel;

	@BeforeEach
	void setUp() {
		channel = mock(MessageChannel.class);
	}

	private static StompHeaderAccessor accessorOf(StompCommand command) {
		StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
		accessor.setSessionId(SESSION_ID);
		accessor.setLeaveMutable(true);
		return accessor;
	}

	private static Message<byte[]> messageOf(StompHeaderAccessor accessor) {
		return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
	}

	private static StompHeaderAccessor authenticated(StompCommand command) {
		StompHeaderAccessor accessor = accessorOf(command);
		accessor.setUser(new UsernamePasswordAuthenticationToken(MEMBER_ID, null, List.of()));
		return accessor;
	}

	@Nested
	@DisplayName("CONNECT")
	class Connect {

		@Test
		@DisplayName("Authorization: Bearer 토큰이 유효하면 세션에 회원을 붙인다")
		void validToken_setsUser() {
			StompHeaderAccessor accessor = accessorOf(StompCommand.CONNECT);
			accessor.setNativeHeader("Authorization", "Bearer good");
			given(jwtProvider.validateToken("good")).willReturn(true);
			given(jwtProvider.getMemberId("good")).willReturn(MEMBER_ID);

			Message<?> result = interceptor.preSend(messageOf(accessor), channel);

			StompHeaderAccessor resultAccessor = MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
			assertThat(resultAccessor.getUser()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
			assertThat(((UsernamePasswordAuthenticationToken)resultAccessor.getUser()).getPrincipal())
				.isEqualTo(MEMBER_ID);
		}

		@Test
		@DisplayName("헤더가 없으면 거절 — ERROR 프레임으로 연결이 닫힌다")
		void missingHeader_rejected() {
			StompHeaderAccessor accessor = accessorOf(StompCommand.CONNECT);

			assertThatThrownBy(() -> interceptor.preSend(messageOf(accessor), channel))
				.isInstanceOf(MessageDeliveryException.class);
			then(jwtProvider).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("토큰이 유효하지 않으면 거절")
		void invalidToken_rejected() {
			StompHeaderAccessor accessor = accessorOf(StompCommand.CONNECT);
			accessor.setNativeHeader("Authorization", "Bearer expired");
			given(jwtProvider.validateToken("expired")).willReturn(false);

			assertThatThrownBy(() -> interceptor.preSend(messageOf(accessor), channel))
				.isInstanceOf(MessageDeliveryException.class);
			then(jwtProvider).should(never()).getMemberId(any());
		}
	}

	@Nested
	@DisplayName("SUBSCRIBE")
	class Subscribe {

		@Test
		@DisplayName("/user/queue/courses/{id} 를 볼 수 있는 회원이면 명단에 올린다")
		void viewableCourse_registers() {
			StompHeaderAccessor accessor = authenticated(StompCommand.SUBSCRIBE);
			accessor.setDestination("/user/queue/courses/10");
			accessor.setSubscriptionId("sub-1");
			Course course = mock(Course.class);
			given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
			given(courseAccessPolicy.canView(course, MEMBER_ID, null)).willReturn(true);

			interceptor.preSend(messageOf(accessor), channel);

			then(registry).should().subscribe(COURSE_ID, MEMBER_ID, SESSION_ID, "sub-1");
		}

		@Test
		@DisplayName("볼 수 없는 코스는 거절하고 명단에 올리지 않는다")
		void forbiddenCourse_rejected() {
			StompHeaderAccessor accessor = authenticated(StompCommand.SUBSCRIBE);
			accessor.setDestination("/user/queue/courses/10");
			accessor.setSubscriptionId("sub-1");
			Course course = mock(Course.class);
			given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
			given(courseAccessPolicy.canView(course, MEMBER_ID, null)).willReturn(false);

			assertThatThrownBy(() -> interceptor.preSend(messageOf(accessor), channel))
				.isInstanceOf(MessageDeliveryException.class);
			then(registry).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("없는 코스·다른 목적지·인증 안 된 세션은 거절")
		void invalidCases_rejected() {
			StompHeaderAccessor missing = authenticated(StompCommand.SUBSCRIBE);
			missing.setDestination("/user/queue/courses/10");
			given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.empty());
			assertThatThrownBy(() -> interceptor.preSend(messageOf(missing), channel))
				.isInstanceOf(MessageDeliveryException.class);

			StompHeaderAccessor otherDestination = authenticated(StompCommand.SUBSCRIBE);
			otherDestination.setDestination("/topic/anything");
			assertThatThrownBy(() -> interceptor.preSend(messageOf(otherDestination), channel))
				.isInstanceOf(MessageDeliveryException.class);

			StompHeaderAccessor anonymous = accessorOf(StompCommand.SUBSCRIBE);
			anonymous.setDestination("/user/queue/courses/10");
			assertThatThrownBy(() -> interceptor.preSend(messageOf(anonymous), channel))
				.isInstanceOf(MessageDeliveryException.class);

			then(registry).shouldHaveNoInteractions();
		}
	}

	@Test
	@DisplayName("UNSUBSCRIBE·DISCONNECT 는 명단에서 뺀다")
	void unsubscribeAndDisconnect_updateRegistry() {
		StompHeaderAccessor unsubscribe = authenticated(StompCommand.UNSUBSCRIBE);
		unsubscribe.setSubscriptionId("sub-1");
		interceptor.preSend(messageOf(unsubscribe), channel);
		then(registry).should().unsubscribe(SESSION_ID, "sub-1");

		interceptor.preSend(messageOf(authenticated(StompCommand.DISCONNECT)), channel);
		then(registry).should().disconnect(SESSION_ID);
	}

	@Test
	@DisplayName("STOMP 프레임이 아닌 메시지는 그대로 통과한다")
	void nonStompMessage_passesThrough() {
		Message<byte[]> plain = MessageBuilder.withPayload(new byte[0]).build();

		assertThat(interceptor.preSend(plain, channel)).isSameAs(plain);
	}
}
