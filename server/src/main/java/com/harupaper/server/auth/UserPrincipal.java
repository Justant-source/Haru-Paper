package com.harupaper.server.auth;

import com.harupaper.server.user.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security가 세션에 담는 인증 주체. User 엔티티를 감싼다.
 * 컨트롤러는 {@code @AuthenticationPrincipal UserPrincipal principal}로 현재 사용자를 받고,
 * {@code principal.userId()}를 owner_user_id 스코핑에 쓴다.
 */
@Getter
public class UserPrincipal implements UserDetails {

    private final User user;

    public UserPrincipal(User user) {
        this.user = user;
    }

    public String userId() {
        return user.getId();
    }

    public String handle() {
        return user.getHandle();
    }

    public boolean isAdmin() {
        return "admin".equals(user.getRole());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().toUpperCase()));
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    /** 로그인 식별자는 이메일이다(핸들은 표시용, 4.1절). */
    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !"suspended".equals(user.getStatus());
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return "active".equals(user.getStatus());
    }
}
