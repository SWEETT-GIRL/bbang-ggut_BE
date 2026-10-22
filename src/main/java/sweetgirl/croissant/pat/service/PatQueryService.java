package sweetgirl.croissant.pat.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.pat.dto.response.PatListResponse;
import sweetgirl.croissant.pat.entity.MemberPat;
import sweetgirl.croissant.pat.repository.MemberPatRepository;
import sweetgirl.croissant.profile.dto.response.CharacterFace;
import sweetgirl.croissant.profile.service.CharacterFaceReader;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PatQueryService {

	static final int MAX_DAYS = 30;
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final MemberPatRepository memberPatRepository;
	private final CharacterFaceReader characterFaceReader;
	private final MemberFollowRepository memberFollowRepository;

	/**
	 * 최근 {@code days} 일 동안 나에게 톡 친 사람 — 홈 한 줄("오늘 OO님 외 N명")과 목록. 게스트는 받은 게 없어 빈 목록이다.
	 * 같은 사람의 같은 날은 한 줄이고, 오늘 요약은 가장 최근에 친 사람이 앞이다.
	 */
	public PatListResponse getReceived(Long memberId, int days) {
		LocalDate today = LocalDate.now(KST);
		LocalDate since = today.minusDays(Math.min(Math.max(days, 1), MAX_DAYS) - 1L);
		List<MemberPat> pats = memberPatRepository.findReceivedSince(memberId, since);
		List<Long> senderIds = pats.stream().map(pat -> pat.getFromMember().getId()).distinct().toList();
		Map<Long, CharacterFace> faces = characterFaceReader.facesOf(senderIds);
		Set<Long> followedByMe = senderIds.isEmpty() ? Set.of()
			: Set.copyOf(memberFollowRepository.findFolloweeIdsIn(memberId, senderIds));
		Set<Long> followsMe = senderIds.isEmpty() ? Set.of()
			: Set.copyOf(memberFollowRepository.findFollowerIdsIn(memberId, senderIds));
		List<PatListResponse.PatResponse> items = pats.stream()
			.map(pat -> PatListResponse.PatResponse.of(pat, faces.get(pat.getFromMember().getId()),
				followedByMe.contains(pat.getFromMember().getId()), followsMe.contains(pat.getFromMember().getId())))
			.toList();
		return PatListResponse.of(today, items);
	}
}
