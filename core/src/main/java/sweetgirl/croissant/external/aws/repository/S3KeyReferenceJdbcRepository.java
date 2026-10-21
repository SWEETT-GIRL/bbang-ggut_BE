package sweetgirl.croissant.external.aws.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * S3 정리가 지우면 안 되는 키를 DB 전체에서 모은다.
 *
 * <p>이미지 엔티티에는 {@code @SQLRestriction("processing_status = 'DONE'")}이 걸려 있어 JPA 로
 * 읽으면 처리 중(PENDING)·실패(FAILED) 행이 빠진다. 그 행의 파일까지 주인 없는 것으로 보면 안 되므로
 * 네이티브 SQL 로 읽는다.
 */
@Repository
@RequiredArgsConstructor
public class S3KeyReferenceJdbcRepository {

	/** {@code stores/}·{@code menus/}·{@code bread-records/} 아래 원본이나 그 크기 조정본을 가리키는 값만 남긴다. */
	private static final String TARGET_KEY_PATTERN = "^(resized/[0-9]+/|)(stores|menus|bread-records)/";

	/** JSON 안에 문자열 값으로 들어 있는 키를 뽑아낸다. */
	private static final String EMBEDDED_KEY_PATTERN = "\"((resized/[0-9]+/|)(stores|menus|bread-records)/[^\"]+)\"";

	private static final String PROTECTED_KEYS_SQL = """
			SELECT DISTINCT referenced.aws_key
			FROM (
				-- 주 테이블에서 정상 경로로는 잡히지 않는 행: 처리 중·실패 행, 다른 폴더의 키를 든 행
				SELECT aws_key FROM store_image_file WHERE processing_status <> 'DONE' OR aws_key NOT LIKE 'stores/%%'
				UNION ALL
				SELECT aws_key FROM menu_image_file WHERE processing_status <> 'DONE' OR aws_key NOT LIKE 'menus/%%'
				UNION ALL
				SELECT aws_key FROM bread_record_photo WHERE aws_key NOT LIKE 'bread-records/%%'
				-- 키를 클라이언트가 보낸 대로 저장하는 테이블 (접두사 검증 없음)
				UNION ALL
				SELECT aws_key FROM review_image_file
				UNION ALL
				SELECT aws_key FROM course_image_files
				UNION ALL
				SELECT aws_key FROM store_contribution_report_image_file
				UNION ALL
				SELECT aws_key FROM menu_contribution_report_image_file
				UNION ALL
				SELECT thumbnail_key FROM banner
				UNION ALL
				SELECT detail_key FROM banner
				UNION ALL
				SELECT image_key FROM letter_message_image
				UNION ALL
				SELECT image_key FROM world_planets
				UNION ALL
				SELECT image_key FROM world_planet_stages
				UNION ALL
				SELECT image_key FROM world_character_assets
				UNION ALL
				SELECT image_key FROM monthly_report_asset
				UNION ALL
				SELECT image_key FROM monthly_report_asset_replacement
				UNION ALL
				SELECT original_image_key FROM monthly_report_asset_replacement
				UNION ALL
				SELECT character_image_key FROM monthly_report_member
				-- 리사이즈 콜백이 먼저 와서 행 저장을 기다리는 파일
				UNION ALL
				SELECT aws_key FROM image_processing_callback
				-- 자기 원본과 다른 원본의 크기 조정본을 가리키는 행
				UNION ALL
				SELECT resized.resized_key
				FROM (
					SELECT aws_key, resized_key_320, resized_key_640, resized_key_1024 FROM store_image_file
					UNION ALL
					SELECT aws_key, resized_key_320, resized_key_640, resized_key_1024 FROM menu_image_file
					UNION ALL
					SELECT aws_key, resized_key_320, resized_key_640, resized_key_1024 FROM bread_record_photo
					UNION ALL
					SELECT aws_key, resized_key_320, resized_key_640, resized_key_1024 FROM review_image_file
					UNION ALL
					SELECT aws_key, resized_key_320, resized_key_640, resized_key_1024 FROM course_image_files
					UNION ALL
					SELECT aws_key, resized_key_320, resized_key_640, resized_key_1024
					FROM store_contribution_report_image_file
					UNION ALL
					SELECT aws_key, resized_key_320, resized_key_640, resized_key_1024
					FROM menu_contribution_report_image_file
				) image
				CROSS JOIN LATERAL (
					VALUES (image.resized_key_320), (image.resized_key_640), (image.resized_key_1024)
				) resized(resized_key)
				WHERE resized.resized_key IS NOT NULL
					AND resized.resized_key NOT IN (
						'resized/320/' || image.aws_key,
						'resized/640/' || image.aws_key,
						'resized/1024/' || image.aws_key)
				-- 발행된 월간 보고서·초안·제보 원문에 박혀 있는 키
				UNION ALL
				SELECT (regexp_matches(CAST(payload AS text), '%1$s', 'g'))[1] FROM monthly_report_member
				UNION ALL
				SELECT (regexp_matches(CAST(payload AS text), '%1$s', 'g'))[1] FROM monthly_report_draft
				UNION ALL
				SELECT (regexp_matches(CAST(draft AS text), '%1$s', 'g'))[1] FROM monthly_report_job
				UNION ALL
				SELECT (regexp_matches(message, '%1$s', 'g'))[1] FROM store_contribution_report
				UNION ALL
				SELECT (regexp_matches(message, '%1$s', 'g'))[1] FROM menu_contribution_report
			) referenced
			WHERE referenced.aws_key ~ '%2$s'
			"""
		.formatted(EMBEDDED_KEY_PATTERN, TARGET_KEY_PATTERN);

	private final JdbcTemplate jdbcTemplate;

	/**
	 * 가게·메뉴 이미지의 정상(DONE) 행 말고도 대상 폴더의 파일을 가리키는 값 전부.
	 *
	 * <p>원본 키일 수도, {@code resized/{폭}/{원본키}} 형태일 수도 있다. 대상 폴더와 무관한 값은 DB 에서 걸러 낸다.
	 * 테이블 하나라도 읽지 못하면 예외로 끝난다 — 참조를 다 확인하지 못한 채 지우는 일이 없게 한다.
	 */
	public List<String> findProtectedKeys() {
		return jdbcTemplate.queryForList(PROTECTED_KEYS_SQL, String.class);
	}

	/** 빵 기록 사진이 쓰는 원본 키. 처리 상태로 거르지 않는다. */
	public List<String> findBreadRecordPhotoKeys() {
		return jdbcTemplate.queryForList("SELECT DISTINCT aws_key FROM bread_record_photo", String.class);
	}
}
