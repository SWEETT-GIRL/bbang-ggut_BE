package sweetgirl.croissant.member.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class MemberService {

	private final MemberRepository memberRepository;
	private final SubscribedStoreRepository subscribedStoreRepository;

	@Transactional(readOnly = true)
	public long getSubscribedStoreCount(Member member) {
		return subscribedStoreRepository.countByMember(member);
	}

}
