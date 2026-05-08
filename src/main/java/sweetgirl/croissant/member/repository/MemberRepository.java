package sweetgirl.croissant.member.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.member.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {

}
