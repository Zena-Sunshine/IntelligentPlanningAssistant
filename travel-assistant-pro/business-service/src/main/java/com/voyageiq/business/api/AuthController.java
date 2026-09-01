package com.voyageiq.business.api;

import com.voyageiq.business.domain.UserAccount;
import com.voyageiq.business.repository.UserAccountRepository;
import com.voyageiq.business.security.JwtTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final UserAccountRepository users;
    private final JwtTokenService tokens;

    public AuthController(AuthenticationConfiguration configuration, UserAccountRepository users,
                          JwtTokenService tokens) throws Exception {
        this.authenticationManager = configuration.getAuthenticationManager();
        this.users = users;
        this.tokens = tokens;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (org.springframework.security.core.AuthenticationException error) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        UserAccount user = users.findByUsernameIgnoreCase(request.username()).orElseThrow();
        JwtTokenService.IssuedToken token = tokens.issue(user);
        return new LoginResponse(token.value(), token.expiresAt(), UserView.from(user));
    }

    @GetMapping("/me")
    public UserView me(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return users.findById(jwt.getSubject()).map(UserView::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record LoginResponse(String accessToken, Instant expiresAt, UserView user) {}
    public record UserView(String id, String username, String displayName, String tenantId, String role) {
        static UserView from(UserAccount user) {
            return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getTenantId(), user.getRole());
        }
    }
}

