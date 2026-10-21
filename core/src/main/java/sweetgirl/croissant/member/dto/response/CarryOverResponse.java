package sweetgirl.croissant.member.dto.response;

/**
 * 로그인하면 따라올 것들. 로그인 게이트가 문구를 고르는 데 쓴다.
 *
 * <p>게스트에는 두 부류가 섞여 있다 — 개편 전부터 쓰며 쌓아둔 사람과, 방금 들어온 사람.
 * 앞사람에게는 "빵투어 3개와 기록 12개가 그대로 따라와요"라고 말해야 하고, 뒷사람에게 같은 말을 하면
 * "0개가 따라와요"가 된다. 그 갈림을 앱이 한 번의 호출로 정하게 하려고 숫자를 모아 내린다.
 *
 * @param guest           게스트인가. 로그인한 회원이면 문구를 띄울 일이 없다
 * @param hasAnything     하나라도 쌓아둔 게 있는가. 앱이 매번 세 숫자를 더하지 않게 서버가 답한다
 */
public record CarryOverResponse(
		boolean guest,
		long recordCount,
		long courseCount,
		long savedStoreCount,
		boolean hasAnything) {

	public static CarryOverResponse of(
			boolean guest,
			long recordCount,
			long courseCount,
			long savedStoreCount) {
		return new CarryOverResponse(
			guest,
			recordCount,
			courseCount,
			savedStoreCount,
			recordCount + courseCount + savedStoreCount > 0);
	}
}
