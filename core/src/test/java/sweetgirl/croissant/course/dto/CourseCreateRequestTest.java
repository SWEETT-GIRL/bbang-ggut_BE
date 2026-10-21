package sweetgirl.croissant.course.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.course.dto.request.CourseCreateRequest;

/**
 * 이 DTO는 서비스 테스트에서 늘 mock으로 쓰여서, 정작 JSON이 필드에 안 꽂히는 걸
 * 아무도 못 잡았다. 여기서는 실제로 역직렬화해 본다.
 */
class CourseCreateRequestTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("isPublic: true를 보내면 값이 들어온다")
	void deserializes_isPublic() throws Exception {
		CourseCreateRequest request = objectMapper.readValue(
			"{\"title\":\"나의 빵지순례\",\"isPublic\":true}", CourseCreateRequest.class);

		assertThat(request.getIsPublic()).isTrue();
	}

	@Test
	@DisplayName("안 보내면 null이다 — 호출부가 비공개로 본다")
	void omitted_isNull() throws Exception {
		CourseCreateRequest request = objectMapper.readValue(
			"{\"title\":\"나의 빵지순례\"}", CourseCreateRequest.class);

		assertThat(request.getIsPublic()).isNull();
	}
}
