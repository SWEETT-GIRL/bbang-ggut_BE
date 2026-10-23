package sweetgirl.croissant.member.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.member.entity.NicknameHistory;

public interface NicknameHistoryRepository extends JpaRepository<NicknameHistory, Long> {

	// 쓰기만 한다. 읽기는 어드민 사칭 검토 때 붙인다.
}
