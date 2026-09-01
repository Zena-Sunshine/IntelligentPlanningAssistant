package com.voyageiq.business.security;

import com.voyageiq.business.config.VoyageIqProperties;
import com.voyageiq.business.domain.UserAccount;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {
    private final JwtEncoder encoder;
    private final VoyageIqProperties properties;

    public JwtTokenService(JwtEncoder encoder, VoyageIqProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public IssuedToken issue(UserAccount user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.security().tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("voyageiq-business-service")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.getId())
                .claim("username", user.getUsername())
                .claim("name", user.getDisplayName())
                .claim("tenant_id", user.getTenantId())
                .claim("roles", java.util.List.of(user.getRole()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {}
}

