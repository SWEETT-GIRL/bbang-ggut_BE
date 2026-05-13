package sweetgirl.croissant.member.service;

import java.net.URL;
import java.util.Date;

import org.springframework.stereotype.Component;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;

import sweetgirl.croissant.member.dto.dto.AppleTokenClaims;

@Component
public class AppleTokenVerifier {

	private static final String APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys";
	private static final String APPLE_ISSUER = "https://appleid.apple.com";

	public AppleTokenClaims verify(String identityToken) {
		try {
			SignedJWT jwt = SignedJWT.parse(identityToken);
			String kid = jwt.getHeader().getKeyID();

			JWKSet jwkSet = JWKSet.load(new URL(APPLE_JWKS_URL));
			RSAKey rsaKey = (RSAKey)jwkSet.getKeyByKeyId(kid);
			if (rsaKey == null) {
				throw new RuntimeException("Apple 공개키 없음: kid=" + kid);
			}

			if (!jwt.verify(new RSASSAVerifier(rsaKey.toRSAPublicKey()))) {
				throw new RuntimeException("identityToken 서명 검증 실패");
			}

			var claims = jwt.getJWTClaimsSet();

			if (!APPLE_ISSUER.equals(claims.getIssuer())) {
				throw new RuntimeException("잘못된 issuer");
			}

			if (claims.getExpirationTime().before(new Date())) {
				throw new RuntimeException("만료된 토큰");
			}

			return new AppleTokenClaims(claims.getSubject(), (String)claims.getClaim("email"));
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Apple 토큰 검증 실패", e);
		}
	}
}
