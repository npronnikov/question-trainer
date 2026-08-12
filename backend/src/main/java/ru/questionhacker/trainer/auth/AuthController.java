package ru.questionhacker.trainer.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AppUser register(@Valid @RequestBody RegisterRequest body,
                            HttpServletRequest request, HttpServletResponse response) {
        return auth.register(body.username(), body.email(), body.password(), request, response);
    }

    @PostMapping("/login")
    public AppUser login(@Valid @RequestBody LoginRequest body,
                         HttpServletRequest request, HttpServletResponse response) {
        return auth.login(body.login(), body.password(), request, response);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }

    @GetMapping("/me")
    public AppUser me() {
        return auth.requireCurrentUser();
    }

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 80) String username,
            @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 200) String password) {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 254) String login,
            @NotBlank @Size(max = 200) String password) {
    }

    public record CsrfResponse(String headerName, String token) {
    }
}
