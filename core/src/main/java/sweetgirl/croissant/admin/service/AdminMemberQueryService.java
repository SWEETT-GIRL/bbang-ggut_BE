package sweetgirl.croissant.admin.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.response.AdminMemberSummaryResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

/**
 * 어드민 회원 조회. 지금은 편지 수신자를 고르기 위한 검색 하나뿐이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminMemberQueryService {

	private static final int MAX_PAGE_SIZE = 50;

	private final MemberRepository memberRepository;

	public AdminMemberSummaryResponse.ListResponse search(String keyword, int page, int size) {
		Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
		Slice<Member> slice = memberRepository.searchForAdmin(keyword == null ? "" : keyword.trim(), pageable);

		return new AdminMemberSummaryResponse.ListResponse(
			slice.getContent().stream().map(AdminMemberSummaryResponse::from).toList(),
			slice.hasNext());
	}
}
