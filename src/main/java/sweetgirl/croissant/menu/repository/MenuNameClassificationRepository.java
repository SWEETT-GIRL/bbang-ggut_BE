package sweetgirl.croissant.menu.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import sweetgirl.croissant.menu.entity.MenuNameClassification;

@Repository
public interface MenuNameClassificationRepository extends JpaRepository<MenuNameClassification, String> {
	// 파생 쿼리 없음 — 캐시 조회는 findAllById(정규화 키), 저장은 saveAll 로 충분하다
}
