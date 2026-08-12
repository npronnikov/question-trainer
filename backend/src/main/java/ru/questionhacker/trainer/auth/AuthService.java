package ru.questionhacker.trainer.auth;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Неверный логин или пароль";
    private static final String DUPLICATE_LOGIN = "Имя пользователя или email уже заняты";

    private final UserAccountRepository users;
    private final PasswordEncoder passwords;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContexts;

    public AuthService(UserAccountRepository users, PasswordEncoder passwords,
                       AuthenticationManager authenticationManager,
                       SecurityContextRepository securityContexts) {
        this.users = users;
        this.passwords = passwords;
        this.authenticationManager = authenticationManager;
        this.securityContexts = securityContexts;
    }

    @Transactional
    public AppUser register(String username, String email, String password,
                            HttpServletRequest request, HttpServletResponse response) {
        if (users.usernameExists(username) || users.emailExists(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, DUPLICATE_LOGIN);
        }
        try {
            users.create(username, email, passwords.encode(password), Set.of("USER"), false);
        } catch (RuntimeException error) {
            if (users.usernameExists(username) || users.emailExists(email)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, DUPLICATE_LOGIN, error);
            }
            throw error;
        }
        return authenticate(username, password, request, response);
    }

    public AppUser login(String login, String password,
                         HttpServletRequest request, HttpServletResponse response) {
        return authenticate(login, password, request, response);
    }

    public AppUser requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется вход");
        }
        return users.findPublicByLogin(authentication.getName())
                .filter(AppUser::enabled)
                .filter(user -> !user.systemAccount())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется вход"));
    }

    private AppUser authenticate(String login, String password,
                                 HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(login.strip(), password));
        } catch (AuthenticationException error) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        }

        if (request.getSession(false) == null) {
            request.getSession(true);
        } else {
            request.changeSessionId();
        }

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);

        return users.findPublicByLogin(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS));
    }
}
