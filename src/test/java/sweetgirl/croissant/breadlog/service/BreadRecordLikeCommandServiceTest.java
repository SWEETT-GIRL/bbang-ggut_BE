package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.util.Optional;

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
import sweetgirl.croissant.breadlog.repository.BreadRecordLikeRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;

@ExtendWith(MockitoExtension.class)
@DisplayName("BreadRecordLikeCommandService")
class BreadRecordLikeCommandServiceTest {

	private static final Long ME = 2L;
	private static final Long RECORD = 10L;

	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private BreadRecordLikeRepository breadRecordLikeRepository;
	@Mock
	private BreadRecordVisibilityPolicy visibilityPolicy;
	@Mock
	private MemberReader memberReader;
	@Mock
	private MemberRestrictionReader memberRestrictionReader;

	@InjectMocks
	private BreadRecordLikeCommandService likeCommandService;

	private BreadRecord record(boolean isPublic) {
		Member author = Member.builder().nickname("작성자").build();
		ReflectionTestUtils.setField(author, "id", 1L);
		BreadRecord record = BreadRecord.builder().member(author).storeName("밀도").isPublic(isPublic).build();
		ReflectionTestUtils.setField(record, "id", RECORD);
		return record;
	}

	@Test
	@DisplayName("볼 수 있는 공개 기록이면 한 줄 넣는다(있으면 0, 멱등)")
	void likes() {
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(RECORD)).willReturn(Optional.of(record(true)));

		likeCommandService.like(ME, RECORD);

		then(visibilityPolicy).should().validateCanView(eq(ME), any());
		then(breadRecordLikeRepository).should().insertIfAbsent(eq(RECORD), eq(ME), any());
	}

	@Test
	@DisplayName("내 비공개 기록은 볼 수는 있어도 좋아요는 안 된다 — 공개만")
	void myPrivateRecord() {
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(RECORD)).willReturn(Optional.of(record(false)));

		assertThatThrownBy(() -> likeCommandService.like(ME, RECORD))
			.isInstanceOf(BreadLogException.class)
			.extracting("errorCode").isEqualTo(BreadLogErrorCode.BREAD_RECORD_NOT_VISIBLE);
		then(breadRecordLikeRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("없는 기록은 404, 게스트는 MEMBER403_GUEST, 정지는 403 — 기록을 읽기 전에 막힌다")
	void rejected() {
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(RECORD)).willReturn(Optional.empty());
		assertThatThrownBy(() -> likeCommandService.like(ME, RECORD))
			.isInstanceOf(BreadLogException.class)
			.extracting("errorCode").isEqualTo(BreadLogErrorCode.BREAD_RECORD_NOT_FOUND);

		willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED)).given(memberReader).requireLoggedIn(3L);
		assertThatThrownBy(() -> likeCommandService.like(3L, RECORD)).isInstanceOf(MemberException.class);

		willThrow(new MemberException(MemberErrorCode.MEMBER_WRITE_RESTRICTED))
			.given(memberRestrictionReader).validateCanInteract(4L);
		assertThatThrownBy(() -> likeCommandService.like(4L, RECORD)).isInstanceOf(MemberException.class);
		then(breadRecordLikeRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("취소는 있든 없든 지우고 끝, 제재는 보지 않는다")
	void unlikes() {
		likeCommandService.unlike(ME, RECORD);

		then(breadRecordLikeRepository).should().deleteByPair(RECORD, ME);
		then(memberRestrictionReader).shouldHaveNoInteractions();
	}
}
