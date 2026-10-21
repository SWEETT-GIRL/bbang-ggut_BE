package sweetgirl.croissant.inquiry.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.inquiry.entity.Inquiry;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

}
