package ru.questionhacker.trainer.auth;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserAccountRepository users;

    public DatabaseUserDetailsService(UserAccountRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String login) throws UsernameNotFoundException {
        UserAccountRepository.AccountRow account = users.findAccountByLogin(login)
                .filter(row -> !row.systemAccount())
                .orElseThrow(() -> new UsernameNotFoundException("Неверный логин или пароль"));

        String[] authorities = account.roles().stream()
                .map(role -> "ROLE_" + role)
                .toArray(String[]::new);

        return User.withUsername(account.username())
                .password(account.passwordHash())
                .authorities(authorities)
                .disabled(!account.enabled())
                .build();
    }
}
