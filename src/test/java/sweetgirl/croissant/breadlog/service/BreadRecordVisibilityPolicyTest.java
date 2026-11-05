package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.follow.service.FollowVisibilityReader;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("BreadRecordVisibilityPolicy")
class BreadRecordVisibilityPolicyTest {

	private static final Long AUTHOR = 1L;
	private static final Long VIEWER = 2L;

	@Mock
	private MemberBlockRepository memberBlockRepository;
	@Mock
	private FollowVisibilityReader followVisibilityReader;

	@InjectMocks
	private BreadRecordVisibilityPolicy policy;

	@BeforeEach
	void publicAccountByDefault() {
		lenient().when(followVisibilityReader.canViewRecords(any(), any())).thenReturn(true);
	}

	private BreadRecord record(boolean isPublic) {
		Member author = Member.builder().nickname("작성자").build();
		ReflectionTestUtils.setField(author, "id", AUTHOR);
		BreadRecord record = BreadRecord.builder().member(author).storeName("밀도").isPublic(isPublic).build();
		ReflectionTestUtils.setField(record, "id", 10L);
		return record;
	}

	@Test
	@DisplayName("남의 공개 기록은 차단 관계가 아니면 보이고, 비로그인도 본다")
	void publicVisible() {
		BreadRecord record = record(true);
		given(memberBlockRepository.existsBetween(VIEWER, AUTHOR)).willReturn(false);

		assertThat(policy.canView(VIEWER, record)).isTrue();
		assertThat(policy.canView(null, record)).isTrue();
	}

	@Test
	@DisplayName("비공개 · 숨김 · 차단 · 작성자 탈퇴면 403 BREAD_RECORD403_NOT_VISIBLE")
	void notVisible() {
		assertThatThrownBy(() -> policy.validateCanView(VIEWER, record(false)))
			.isInstanceOf(BreadLogException.class)
			.extracting("errorCode").isEqualTo(BreadLogErrorCode.BREAD_RECORD_NOT_VISIBLE);

		BreadRecord hidden = record(true);
		hidden.hide("신고 3건", "SYSTEM");
		assertThat(policy.canView(VIEWER, hidden)).isFalse();

		BreadRecord blocked = record(true);
		given(memberBlockRepository.existsBetween(VIEWER, AUTHOR)).willReturn(true);
		assertThat(policy.canView(VIEWER, blocked)).isFalse();

		BreadRecord withdrawn = record(true);
		withdrawn.getMember().softDelete();
		assertThat(policy.canView(null, withdrawn)).isFalse();
	}

	@Test
	@DisplayName("작성자가 비공개 계정이고 내가 팔로워가 아니면 공개 기록도 못 본다")
	void privateAccount() {
		BreadRecord record = record(true);
		given(followVisibilityReader.canViewRecords(VIEWER, record.getMember())).willReturn(false);

		assertThat(policy.canView(VIEWER, record)).isFalse();
	}

	@Test
	@DisplayName("내 기록은 비공개 · 숨김이어도 본다")
	void mine() {
		BreadRecord mine = record(false);
		mine.hide("신고 3건", "SYSTEM");

		assertThat(policy.canView(AUTHOR, mine)).isTrue();
	}
}
