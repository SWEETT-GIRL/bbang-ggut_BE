package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.MemberRestriction;
import sweetgirl.croissant.member.enums.MemberRestrictionType;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemberRestrictionReader")
class MemberRestrictionReaderTest {

	@Mock
	private MemberRestrictionRepository memberRestrictionRepository;
	@Mock
	private MemberReader memberReader;

	@InjectMocks
	private MemberRestrictionReader memberRestrictionReader;

	@Test
	@DisplayName("정지 · 강퇴만 본다 — 작성 제한은 상호작용을 막지 않는다")
	void onlySuspendedAndBanned() {
		given(memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(
			eq(1L), eq(List.of(MemberRestrictionType.SUSPENDED, MemberRestrictionType.BANNED)), any()))
			.willReturn(List.of());

		assertThatCode(() -> memberRestrictionReader.validateCanInteract(1L)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("정지 중이면 MEMBER403_WRITE_RESTRICTED")
	void suspended() {
		given(memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(eq(1L), any(), any()))
			.willReturn(List.of(mock(MemberRestriction.class)));

		assertThatThrownBy(() -> memberRestrictionReader.validateCanInteract(1L))
			.isInstanceOf(MemberException.class)
			.extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_WRITE_RESTRICTED);
	}

	@Test
	@DisplayName("id 가 없으면 MEMBER401")
	void unauthorized() {
		assertThatThrownBy(() -> memberRestrictionReader.validateCanInteract(null))
			.isInstanceOf(MemberException.class)
			.extracting("errorCode").isEqualTo(MemberErrorCode.UNAUTHORIZED);
	}

	@Nested
	@DisplayName("validateCanWriteRecord — 게스트도 비공개 기록은 쓴다")
	class ValidateCanWriteRecord {

		@BeforeEach
		void guestWriteBlocked() {
			ReflectionTestUtils.setField(memberRestrictionReader, "guestWriteBlocked", true);
			Member guest = Member.registering("게스트", LocalDateTime.of(2026, 11, 1, 10, 0));
			ReflectionTestUtils.setField(guest, "id", 1L);
			ReflectionTestUtils.setField(guest, "memberType", "GUEST");
			lenient().when(memberReader.getMember(1L)).thenReturn(guest);
			lenient().when(memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(eq(1L), any(), any()))
				.thenReturn(List.of());
		}

		@Test
		@DisplayName("게스트 차단 스위치가 켜져 있어도 비공개 기록은 통과한다")
		void guestPrivateRecordPasses() {
			assertThatCode(() -> memberRestrictionReader.validateCanWriteRecord(1L, false)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("게스트가 공개로 저장하면 MEMBER403_GUEST")
		void guestPublishingIsBlocked() {
			assertThatThrownBy(() -> memberRestrictionReader.validateCanWriteRecord(1L, true))
				.isInstanceOf(MemberException.class)
				.extracting("errorCode").isEqualTo(MemberErrorCode.GUEST_LOGIN_REQUIRED);
		}

		@Test
		@DisplayName("작성 제한 중이면 비공개여도 MEMBER403_WRITE_RESTRICTED")
		void restrictedIsBlocked() {
			given(memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(eq(1L), any(), any()))
				.willReturn(List.of(mock(MemberRestriction.class)));
			assertThatThrownBy(() -> memberRestrictionReader.validateCanWriteRecord(1L, false))
				.isInstanceOf(MemberException.class)
				.extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_WRITE_RESTRICTED);
		}
	}
}
