package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.MONTH;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.PAYLOAD;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportNotificationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPublicationService;

@ExtendWith(MockitoExtension.class)
class MonthlyReportPublicationServiceTest {

	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	private final ObjectMapper mapper = new ObjectMapper();
	@Mock
	private MemberReader memberReader;
	@Mock
	private MonthlyReportRepository repository;
	@Mock
	private MonthlyReportNotificationService notificationService;
	private MonthlyReportPublicationService service;
	private MonthlyReportResponse report;

	@BeforeEach
	void setUp() {
		var codec = new MonthlyReportPayloadCodec(mapper, FACTORY.getValidator());
		service = new MonthlyReportPublicationService(memberReader, repository, codec, notificationService);
		report = codec.read(PAYLOAD, 123L, MONTH);
		lenient().when(memberReader.getMember(any())).thenReturn(MonthlyReportFixtures.eligibleMember("현재 이름"));
	}

	@AfterAll
	static void closeFactory() {
		FACTORY.close();
	}

	@Test
	void publishesOnceAndReportsDuplicateWithoutRepublishing() {
		Instant generatedAt = Instant.parse("2026-01-01T00:00:00Z");
		var raw = mapper.createObjectNode().put("records", 12);
		given(repository.publish(eq(123L), eq(MONTH), any(), eq(raw.toString()), eq("test-v1"), eq(generatedAt), any()))
			.willReturn(1, 0);
		assertThat(service.publish(123L, MONTH, report, raw, "test-v1", generatedAt)).isTrue();
		assertThat(service.publish(123L, MONTH, report, raw, "test-v1", generatedAt)).isFalse();
		verify(notificationService, times(1)).recordPublished(any(), eq(YearMonth.parse(MONTH)), any());
	}

	@Test
	void rejectsIncompleteMetadataAndMismatchedOwner() {
		var raw = mapper.createObjectNode().put("records", 12);
		Instant generatedAt = Instant.parse("2026-01-01T00:00:00Z");
		assertInvalid(() -> service.publish(123L, MONTH, report, null, "test-v1", generatedAt));
		assertInvalid(() -> service.publish(123L, MONTH, report, mapper.createObjectNode(), "test-v1", generatedAt));
		assertInvalid(() -> service.publish(123L, MONTH, report, raw, " ", generatedAt));
		assertInvalid(() -> service.publish(123L, MONTH, report, raw, "test-v1", null));
		assertInvalid(() -> service.publish(456L, MONTH, report, raw, "test-v1", generatedAt));
		verifyNoInteractions(repository, notificationService);
	}

	@Test
	void rejectsGenerationBeforeKstMonthEndAndFutureGeneration() {
		var raw = mapper.createObjectNode().put("records", 12);
		assertInvalid(() -> service.publish(123L, MONTH, report, raw, "test-v1",
			Instant.parse("2025-12-31T14:59:59Z")));
		assertInvalid(() -> service.publish(123L, MONTH, report, raw, "test-v1", Instant.now().plusSeconds(3600)));
		verifyNoInteractions(repository, notificationService);
	}

	@Test
	void skipsGuestsBotsDeletedAndNewMembersWithoutNotification() {
		for (String type : new String[] {"GUEST", "BLOG_BOT"}) {
			var member = MonthlyReportFixtures.eligibleMember("제외 회원");
			ReflectionTestUtils.setField(member, "memberType", type);
			given(memberReader.getMember(123L)).willReturn(member);
			assertThat(service.publish(123L, MONTH, report, mapper.createObjectNode().put("records", 0),
				"test-v1", Instant.parse("2026-01-01T00:00:00Z"))).isFalse();
		}
		var member = MonthlyReportFixtures.eligibleMember("탈퇴 회원");
		member.softDelete();
		given(memberReader.getMember(123L)).willReturn(member);
		assertThat(service.publish(123L, MONTH, report, mapper.createObjectNode().put("records", 0),
			"test-v1", Instant.parse("2026-01-01T00:00:00Z"))).isFalse();
		member = MonthlyReportFixtures.eligibleMember("다음 달 가입");
		ReflectionTestUtils.setField(member, "createdAt",
			LocalDateTime.of(2026, 1, 1, 0, 0));
		given(memberReader.getMember(123L)).willReturn(member);
		assertThat(service.publish(123L, MONTH, report, mapper.createObjectNode().put("records", 0),
			"test-v1", Instant.parse("2026-01-01T00:00:00Z"))).isFalse();
		verifyNoInteractions(repository, notificationService);
	}

	private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOf(CustomException.class)
			.extracting("errorCode").isEqualTo(MonthlyReportErrorCode.INVALID_SNAPSHOT);
	}
}
