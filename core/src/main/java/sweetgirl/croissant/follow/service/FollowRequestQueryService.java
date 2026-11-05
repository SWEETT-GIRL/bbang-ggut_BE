package sweetgirl.croissant.follow.service;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.follow.dto.response.FollowRequestResponse;
import sweetgirl.croissant.follow.entity.MemberFollowRequest;
import sweetgirl.croissant.follow.repository.MemberFollowRequestRepository;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.profile.dto.response.CharacterFace;
import sweetgirl.croissant.profile.service.CharacterFaceReader;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FollowRequestQueryService {

	static final int MAX_PAGE_SIZE = 50;

	private final MemberFollowRequestRepository memberFollowRequestRepository;
	private final MemberReader memberReader;
	private final CharacterFaceReader characterFaceReader;

	/** 내가 받은 팔로우 요청. 최신순. 게스트는 403 MEMBER403_GUEST. */
	public SliceResponse<FollowRequestResponse> getReceived(Long memberId, int page, int size) {
		memberReader.requireLoggedIn(memberId);
		Slice<MemberFollowRequest> rows = memberFollowRequestRepository.findReceived(memberId,
			PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)));
		List<Long> requesterIds = rows.getContent().stream().map(request -> request.getRequester().getId()).toList();
		Map<Long, CharacterFace> faces = characterFaceReader.facesOf(requesterIds);
		List<FollowRequestResponse> content = rows.getContent().stream()
			.map(request -> FollowRequestResponse.of(request, faces.get(request.getRequester().getId())))
			.toList();
		return new SliceResponse<>(content, rows.hasNext());
	}

	/** 받은 요청 수 — 마이 탭의 배지. */
	public long countReceived(Long memberId) {
		memberReader.requireLoggedIn(memberId);
		return memberFollowRequestRepository.countByIdTargetId(memberId);
	}
}
