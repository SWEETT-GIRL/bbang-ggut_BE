package sweetgirl.croissant.reward.dto.response;

import java.time.LocalDate;
import java.util.List;

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
 */
public record InviteResponse(
		String code,
		int joinedCount,
		List<Waiting> waiting,
		int rewardedCount,
		int cap,
		int reward,
		InvitedBy invitedBy,
		boolean canEnterCode) {

	/** @param expiresOn 이 날까지 첫 빵을 기록해야 성사된다 */
	public record Waiting(String nickname, LocalDate expiresOn) {
	}

	public record InvitedBy(String nickname) {
	}
}
