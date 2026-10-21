package sweetgirl.croissant.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.coupon.code.CouponErrorCode;
import sweetgirl.croissant.coupon.dto.response.CouponIssueResponse;
import sweetgirl.croissant.coupon.entity.CouponIssue;
import sweetgirl.croissant.coupon.exception.CouponException;
import sweetgirl.croissant.coupon.issuer.CouponIssuer;
import sweetgirl.croissant.coupon.issuer.CouponSoldOutFlags;

@ExtendWith(MockitoExtension.class)
class CouponCommandServiceTest {

	private static final Long COUPON_ID = 1L;
	private static final Long MEMBER_ID = 7L;

	@Mock
	private CouponIssuer couponIssuer;
	/** 기본 스텁이 false 를 돌려주므로 관문 ①을 통과한다 — 여기서 보려는 건 플래그가 아니라 위임과 예외 전파다. */
	@Mock
	private CouponSoldOutFlags soldOutFlags;

	@InjectMocks
	private CouponCommandService couponCommandService;

	private CouponIssue savedIssue(Long id, LocalDateTime issuedAt) {
		CouponIssue couponIssue = CouponIssue.of(COUPON_ID, MEMBER_ID, issuedAt);
		ReflectionTestUtils.setField(couponIssue, "id", id);
		return couponIssue;
	}

	@Test
	@DisplayName("발급에 성공하면 발급 내역을 응답 DTO 로 바꿔 돌려준다")
	void issue_returnsResponse() {
		LocalDateTime issuedAt = LocalDateTime.of(2026, 9, 21, 12, 0);
		given(couponIssuer.issue(eq(COUPON_ID), eq(MEMBER_ID), any()))
			.willReturn(savedIssue(42L, issuedAt));

		CouponIssueResponse response = couponCommandService.issue(COUPON_ID, MEMBER_ID);

		assertThat(response.couponIssueId()).isEqualTo(42L);
		assertThat(response.couponId()).isEqualTo(COUPON_ID);
		assertThat(response.issuedAt()).isEqualTo(issuedAt);
	}

	@Test
	@DisplayName("발급 시각은 서비스가 정해서 넘긴다 — 발급자는 시계를 모른다")
	void issue_passesCurrentTime() {
		LocalDateTime before = LocalDateTime.now();
		given(couponIssuer.issue(eq(COUPON_ID), eq(MEMBER_ID), any()))
			.willReturn(savedIssue(42L, before));

		couponCommandService.issue(COUPON_ID, MEMBER_ID);

		ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
		then(couponIssuer).should().issue(eq(COUPON_ID), eq(MEMBER_ID), now.capture());
		assertThat(now.getValue()).isBetween(before, LocalDateTime.now());
	}

	@Test
	@DisplayName("소진 예외는 그대로 올려보낸다 — 서비스가 삼키면 컨트롤러가 원인별 응답을 못 만든다")
	void issue_propagatesSoldOut() {
		given(couponIssuer.issue(eq(COUPON_ID), eq(MEMBER_ID), any()))
			.willThrow(new CouponException(CouponErrorCode.COUPON_SOLD_OUT));

		assertThatThrownBy(() -> couponCommandService.issue(COUPON_ID, MEMBER_ID))
			.isInstanceOf(CouponException.class)
			.extracting(exception -> ((CouponException)exception).getErrorCode())
			.isEqualTo(CouponErrorCode.COUPON_SOLD_OUT);
	}

	@Test
	@DisplayName("락 대기 타임아웃(붐빔)도 그대로 올려보낸다 — 소진과 구분돼야 측정에서 원인이 갈린다")
	void issue_propagatesBusy() {
		given(couponIssuer.issue(eq(COUPON_ID), eq(MEMBER_ID), any()))
			.willThrow(new CouponException(CouponErrorCode.COUPON_ISSUE_BUSY));

		assertThatThrownBy(() -> couponCommandService.issue(COUPON_ID, MEMBER_ID))
			.isInstanceOf(CouponException.class)
			.extracting(exception -> ((CouponException)exception).getErrorCode())
			.isEqualTo(CouponErrorCode.COUPON_ISSUE_BUSY);
	}
}
