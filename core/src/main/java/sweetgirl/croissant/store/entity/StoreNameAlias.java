package sweetgirl.croissant.store.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.store.enums.StoreNameAliasSource;

/**
 * 가게명 별칭(구명) — 가게 이름이 바뀌어도 이전 이름으로 검색되도록 보존한다.
 *
 * <p>행 적재는 dataops 파이프라인이 담당한다(이름 변경 승인 시 변경 전 이름을 INSERT).
 * 앱은 읽기만 한다. {@link #source}는 실패 검색어 자동 판정이 별칭을 넣던 때(#948) 생긴 구분이다 — 지금은 검색어 교정
 * ({@code search_keyword_correction})으로 처리한다.
 * created_at 은 DB 기본값(now()).
 */
@Entity
@Table(name = "store_name_alias")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreNameAlias {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id", nullable = false)
	private Store store;

	@Column(nullable = false)
	private String alias;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private StoreNameAliasSource source;

	@Column(name = "created_at", insertable = false, updatable = false)
	private LocalDateTime createdAt;

}
