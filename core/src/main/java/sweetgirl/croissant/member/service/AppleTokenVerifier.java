package sweetgirl.croissant.member.service;

import java.io.IOException;
import java.net.URL;
import java.text.ParseException;
import java.util.Date;

import org.springframework.stereotype.Component;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.dto.dto.AppleTokenClaims;
import sweetgirl.croissant.member.exception.MemberException;

@Component
public class AppleTokenVerifier {

	private static final String APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys";
	private static final String APPLE_ISSUER = "https://appleid.apple.com";

	public AppleTokenClaims verify(String identityToken) {
		SignedJWT jwt = parseToken(identityToken);
		String kid = jwt.getHeader().getKeyID();
		if (kid == null) {
			throw invalidToken();
		}

		JWKSet jwkSet = loadAppleKeys();
		RSAKey rsaKey = (RSAKey)jwkSet.getKeyByKeyId(kid);
		if (rsaKey == null) {
			throw invalidToken();
		}

		try {
			if (!jwt.verify(new RSASSAVerifier(rsaKey.toRSAPublicKey()))) {
				throw invalidToken();
			}

			JWTClaimsSet claims = jwt.getJWTClaimsSet();

			if (!APPLE_ISSUER.equals(claims.getIssuer())) {
				throw invalidToken();
			}

			Date expirationTime = claims.getExpirationTime();
			if (expirationTime == null || !expirationTime.after(new Date())) {
				throw invalidToken();
			}

			String subject = claims.getSubject();
			if (subject == null || subject.isBlank()) {
				throw invalidToken();
			}
			return new AppleTokenClaims(subject, (String)claims.getClaim("email"));
		} catch (MemberException ex) {
			throw ex;
		} catch (Exception e) {
			throw invalidToken();
		}
	}

	private SignedJWT parseToken(String identityToken) {
		try {
			return SignedJWT.parse(identityToken);
		} catch (ParseException | RuntimeException ex) {
			throw invalidToken();
		}
	}

	private JWKSet loadAppleKeys() {
		try {
			return JWKSet.load(new URL(APPLE_JWKS_URL));
		} catch (IOException | ParseException ex) {
			throw new IllegalStateException("Apple 공개키 조회 실패", ex);
		}
	}

	private MemberException invalidToken() {
		return new MemberException(MemberErrorCode.SOCIAL_TOKEN_INVALID);
	}
}
