package sweetgirl.croissant.reward.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.reward.enums.InviteFriendStatus;

/**
 * 내 초대 현황.
 *
 * @param code          내 초대 코드
 * @param joinedCount   내 코드를 넣은 사람 수(기한이 지난 초대 포함)
 * @param waiting       코드를 넣었지만 아직 첫 빵을 기록하지 않은 친구들. 기한이 지난 초대는 빠진다
 * @param rewardedCount 성사된 초대 수
 * @param cap           내가 초대 보상을 받을 수 있는 최대 성사 수
 * @param reward        성사 한 건에 받는 부스러기
 * @param invitedBy     나를 초대한 사람. 코드를 넣은 적이 없으면 null
 * @param canEnterCode  지금 코드를 넣을 수 있는가(가입 7 일 이내 · 기록 없음 · 넣은 적 없음 · 재가입 아님)
 * @param friends       내 코드를 넣은 친구 전부, 최근에 넣은 순. WAITING 수는 waiting 길이와, REWARDED 수는 rewardedCount 와 같다
 */
public record InviteResponse(
		String code,
		int joinedCount,
		List<Waiting> waiting,
		int rewardedCount,
		int cap,
		int reward,
		InvitedBy invitedBy,
		boolean canEnterCode,
		List<Friend> friends) {

	/** @param expiresOn 이 날까지 첫 빵을 기록해야 성사된다 */
	public record Waiting(String nickname, LocalDate expiresOn) {
	}

	/**
	 * 내 코드를 넣은 친구 한 명.
	 *
	 * @param joinedAt   내 코드를 넣은 때(member_invites.created_at). 성사 기한은 여기서 센다
	 * @param expiresOn  이 날까지 첫 빵을 기록해야 성사된다. 성사됐으면 null
	 * @param rewardedAt 첫 빵을 기록해 성사된 때. 성사 전이면 null
	 */
	public record Friend(
			String nickname,
			InviteFriendStatus status,
			LocalDateTime joinedAt,
			LocalDate expiresOn,
			LocalDateTime rewardedAt) {
	}

	public record InvitedBy(String nickname) {
	}
}
