package sweetgirl.croissant.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.comment.code.CommentErrorCode;
import sweetgirl.croissant.comment.dto.response.CommunityAgreementResponse;
import sweetgirl.croissant.comment.exception.CommentException;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityAgreementService")
class CommunityAgreementServiceTest {

	@Mock
	private MemberReader memberReader;

	@InjectMocks
	private CommunityAgreementService service;

	private Member member;

	@BeforeEach
	void setUp() {
		member = Member.builder().nickname("빵순이").build();
		ReflectionTestUtils.setField(member, "id", 1L);
		lenient().when(memberReader.getMember(1L)).thenReturn(member);
	}

	@Test
	@DisplayName("동의 전엔 403 COMMUNITY_AGREEMENT_REQUIRED, 동의하면 지금 버전으로 저장된다")
	void agreeFlow() {
		assertThat(service.get(1L).agreed()).isFalse();
		assertThatThrownBy(() -> service.validateAgreed(1L))
			.isInstanceOf(CommentException.class)
			.extracting("errorCode").isEqualTo(CommentErrorCode.COMMUNITY_AGREEMENT_REQUIRED);

		CommunityAgreementResponse response = service.agree(1L);

		assertThat(response.agreed()).isTrue();
		assertThat(response.agreedVersion()).isEqualTo(CommunityAgreementService.CURRENT_VERSION);
		assertThat(response.agreedAt()).isNotNull();
		assertThatCode(() -> service.validateAgreed(1L)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("옛 버전에 동의했으면 agreed 는 false 고 다시 받아야 한다")
	void oldVersion() {
		member.agreeToCommunityRules("2025-01-01", java.time.LocalDateTime.now());

		CommunityAgreementResponse response = service.get(1L);

		assertThat(response.agreed()).isFalse();
		assertThat(response.agreedVersion()).isEqualTo("2025-01-01");
		assertThat(response.currentVersion()).isEqualTo(CommunityAgreementService.CURRENT_VERSION);
	}
}
