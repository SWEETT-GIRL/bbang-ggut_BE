package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.member.dto.response.CarryOverResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.repository.SavedStoreRepository;

@ExtendWith(MockitoExtension.class)
class CarryOverQueryServiceTest {

	private static final Long MEMBER_ID = 1L;

	@Mock
	private MemberReader memberReader;
	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private CourseRepository courseRepository;
	@Mock
	private SavedStoreRepository savedStoreRepository;

	@InjectMocks
	private CarryOverQueryService carryOverQueryService;

	private void givenCounts(boolean guest, long records, long courses, long savedStores) {
		Member member = Member.builder().nickname("빵순이").build();
		if (guest) {
			member.markAsGuest();
		}
		given(memberReader.getMember(MEMBER_ID)).willReturn(member);
		given(breadRecordRepository.countByMemberIdAndDeletedAtIsNull(MEMBER_ID)).willReturn(records);
		given(courseRepository.countByMemberIdAndTypeInAndDeletedAtIsNull(eq(MEMBER_ID), any()))
			.willReturn(courses);
		given(savedStoreRepository.countByMemberId(MEMBER_ID)).willReturn(savedStores);
	}

	@Test
	@DisplayName("쌓아둔 게 있는 게스트는 숫자와 함께 hasAnything 이 참이다")
	void reportsWhatAGuestHasBuiltUp() {
		givenCounts(true, 12L, 3L, 5L);

		CarryOverResponse response = carryOverQueryService.getCarryOver(MEMBER_ID);

		assertThat(response.guest()).isTrue();
		assertThat(response.recordCount()).isEqualTo(12L);
		assertThat(response.courseCount()).isEqualTo(3L);
		assertThat(response.savedStoreCount()).isEqualTo(5L);
		assertThat(response.hasAnything()).isTrue();
	}

	@Test
	@DisplayName("방금 들어온 게스트는 hasAnything 이 거짓 — 앱이 \"0개가 따라와요\"라고 말하지 않게")
	void tellsBrandNewGuestApart() {
		givenCounts(true, 0L, 0L, 0L);

		CarryOverResponse response = carryOverQueryService.getCarryOver(MEMBER_ID);

		assertThat(response.guest()).isTrue();
		assertThat(response.hasAnything()).isFalse();
	}

	@Test
	@DisplayName("로그인한 회원이면 게스트가 아니라고 답한다")
	void marksLoggedInMember() {
		givenCounts(false, 4L, 1L, 0L);

		assertThat(carryOverQueryService.getCarryOver(MEMBER_ID).guest()).isFalse();
	}
}
