package sweetgirl.croissant.store.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.SubscribedStore;

public interface SubscribedStoreRepository extends JpaRepository<SubscribedStore, Long> {

	long countByMember(Member member);

	Slice<SubscribedStore> findAllByMemberOrderByCreatedAtDesc(Member member, Pageable pageable);

}
