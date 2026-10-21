package sweetgirl.croissant.coupon.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.coupon.entity.CouponIssue;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

	long countByCouponId(Long couponId);

	boolean existsByCouponIdAndMemberId(Long couponId, Long memberId);
}
