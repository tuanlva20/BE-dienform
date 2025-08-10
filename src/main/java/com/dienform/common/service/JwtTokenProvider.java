package com.dienform.common.service;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;

@Component
public class JwtTokenProvider {

  private final Algorithm algorithm;
  private final JWTVerifier verifier;

  @Value("${jwt.access-expiration:86400000}")
  private long accessExpirationMs; // default 1 day

  @Value("${jwt.refresh-expiration:2592000}")
  private long refreshExpirationSec; // default 30 days in seconds

  public JwtTokenProvider(@Value("${jwt.secret}") String secret) {
    this.algorithm = Algorithm.HMAC256(secret);
    this.verifier = JWT.require(algorithm).build();
  }

  public String generateAccessToken(String subject, Map<String, String> claims) {
    Instant now = Instant.now();
    Instant exp = now.plusMillis(accessExpirationMs);
    var builder = JWT.create().withSubject(subject).withIssuedAt(Date.from(now))
        .withExpiresAt(Date.from(exp));
    if (claims != null)
      claims.forEach(builder::withClaim);
    return builder.sign(algorithm);
  }

  public String generateRefreshToken(String subject) {
    Instant now = Instant.now();
    Instant exp = now.plusSeconds(refreshExpirationSec);
    return JWT.create().withSubject(subject).withIssuedAt(Date.from(now))
        .withExpiresAt(Date.from(exp)).withClaim("type", "refresh").sign(algorithm);
  }

  public DecodedJWT validateToken(String token) {
    return verifier.verify(token);
  }

  public long getAccessExpirationMs() {
    return accessExpirationMs;
  }

  public long getRefreshExpirationSec() {
    return refreshExpirationSec;
  }
}


