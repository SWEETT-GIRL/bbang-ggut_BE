package sweetgirl.croissant.member.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.member.dto.response.CarryOverResponse;
import sweetgirl.croissant.store.repository.SavedStoreRepository;

/**
 * 게스트가 로그인하면 따라올 것들을 센다.
 *
 * <p>로그인 게이트는 세계관 화면이 아니라 어디서든 뜨므로 {@code /members/me/world} 에 얹지 않고
 * 따로 둔다. 큐레이션 코스는 내가 만든 게 아니므로 빼고, 내가 만든 것과 가져온 것만 센다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CarryOverQueryService {

	private static final List<CourseType> MINE = List.of(CourseType.CUSTOM, CourseType.FORKED);

	private final MemberReader memberReader;
	private final BreadRecordRepository breadRecordRepository;
	private final CourseRepository courseRepository;
	private final SavedStoreRepository savedStoreRepository;

	public CarryOverResponse getCarryOver(Long memberId) {
		return CarryOverResponse.of(
			memberReader.getMember(memberId).isGuest(),
			breadRecordRepository.countByMemberIdAndDeletedAtIsNull(memberId),
			courseRepository.countByMemberIdAndTypeInAndDeletedAtIsNull(memberId, MINE),
			savedStoreRepository.countByMemberId(memberId));
	}
}
