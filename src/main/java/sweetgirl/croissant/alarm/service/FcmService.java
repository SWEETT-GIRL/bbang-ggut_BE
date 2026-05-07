package sweetgirl.croissant.alarm.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.dto.request.FcmTokenRequest;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.common.enums.DeviceOs;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class FcmService {

	final FcmTokenRepository fcmTokenRepository;
	final MemberRepository memberRepository;

	public void saveOrUpdateToken(Long memberId, FcmTokenRequest fcmTokenRequest) {

		Member member = memberRepository.findById(memberId).orElseThrow();

		String deviceId = fcmTokenRequest.getDeviceId();
		DeviceOs deviceOs = fcmTokenRequest.getDeviceOs();
		String token = fcmTokenRequest.getToken();

		fcmTokenRepository.findByMemberAndDeviceId(member, deviceId)
			.ifPresentOrElse(
				fcmToken -> fcmToken.updateToken(token),
				() -> fcmTokenRepository.save(FcmToken.builder()
					.member(member)
					.deviceId(deviceId)
					.deviceOs(deviceOs)
					.token(token)
					.build())

			);
	}

}
