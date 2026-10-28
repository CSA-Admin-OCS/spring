package com.open.spring.security;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.function.Function;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.*;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;


@Component
public class JwtTokenUtil {

	// Token lifetime in seconds. Reads the same property as the jwt cookie's max-age
	// (CookieFactory), so the token and its cookie always expire together. A token that
	// expired before its cookie left users "logged in" to the browser but getting 401s.
	@Value("${jwt.cookie.max-age:604800}")
	private long tokenValiditySeconds;

	@Value("${jwt.secret}")
	private String secret;

	@Autowired
	private PersonJpaRepository personJpaRepository;

	private SecretKey getSecretKey() {
		byte[] ptsecret = Base64.getDecoder().decode(this.secret);
		SecretKey k = Keys.hmacShaKeyFor(ptsecret);
		return k;
	}

	//retrieve username from jwt token
	public String getUsernameFromToken(String token) {
		return getClaimFromToken(token, Claims::getSubject);
	}

	//retrieve expiration date from jwt token
	public Date getExpirationDateFromToken(String token) {
		return getClaimFromToken(token, Claims::getExpiration);
	}

	public <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
		final Claims claims = getAllClaimsFromToken(token);
		return claimsResolver.apply(claims);
	}
    //for retrieving any information from token we will need the secret key
	private Claims getAllClaimsFromToken(String token) {
		return Jwts.parser().verifyWith(getSecretKey()).build().parseSignedClaims(token).getPayload();
	}

	//check if the token has expired
	private Boolean isTokenExpired(String token) {
		final Date expiration = getExpirationDateFromToken(token);
		return expiration.before(new Date());
	}

	// Generate token for user
	public String generateToken(UserDetails userDetails, List<String> roles) {
		// Create a map to store JWT claims,
		// ... a claim is information asserted about the logged in user
		Map<String, Object> claims = new HashMap<>();

		// Add a custom claim to the JWT token,
		// ... roles are added for the client/frontend to know what the user can do,
		// ... adding roles to login avoides an extra request to the server
		claims.put("roles", roles);

		// Stamp the token with the person's current tokenVersion so a later password
		// change (which bumps it) invalidates this token on the next validateToken call,
		// instead of it staying valid for the rest of its lifetime.
		Person person = personJpaRepository.findByUid(userDetails.getUsername());
		claims.put("tokenVersion", person != null && person.getTokenVersion() != null ? person.getTokenVersion() : 0L);

		// Call doGenerateToken method to create the JWT token and set the standard claims
		// "sub" (subject) will be the username of the user.
		// "iat" (issued at) will be the current time.
		// "exp" (expiration time) will be the current time plus the configured token validity.
		return doGenerateToken(claims, userDetails.getUsername());
	}

	//while creating the token -
	//1. Define  claims of the token, like Issuer, Expiration, Subject, and the ID
	//2. Sign the JWT using the HS512 algorithm and secret key.
	//3. According to JWS Compact Serialization(https://tools.ietf.org/html/draft-ietf-jose-json-web-signature-41#section-3.1)
	//   compaction of the JWT to a URL-safe string 
	private String doGenerateToken(Map<String, Object> claims, String subject) {

		return Jwts.builder().claims(claims).subject(subject).issuedAt(new Date(System.currentTimeMillis()))
				.expiration(new Date(System.currentTimeMillis() + tokenValiditySeconds * 1000))
				.signWith(getSecretKey()).compact();
	}

	//validate token
	public Boolean validateToken(String token, UserDetails userDetails) {
		final String username = getUsernameFromToken(token);
		if (!username.equals(userDetails.getUsername()) || isTokenExpired(token)) {
			return false;
		}

		Long tokenVersionClaim = getClaimFromToken(token, claims -> claims.get("tokenVersion", Long.class));
		long claimedVersion = tokenVersionClaim != null ? tokenVersionClaim : 0L;

		Person person = personJpaRepository.findByUid(username);
		long currentVersion = (person != null && person.getTokenVersion() != null) ? person.getTokenVersion() : 0L;

		// A mismatch means the password changed since this token was issued.
		return claimedVersion == currentVersion;
	}
}