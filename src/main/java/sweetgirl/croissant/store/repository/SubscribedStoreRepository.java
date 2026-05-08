package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.SubscribedStore;

public interface SubscribedStoreRepository extends JpaRepository<SubscribedStore, Long> {

	long countByMember(Member member);

	List<SubscribedStore> findAllByMember(Member member);

}
