package com.missionboard.auth;

import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void loadsUserWithRolePrefixedAuthority() {
        User user = new User();
        user.setUsername("chief");
        user.setPassword("$2b$10$hash");
        user.setDisplayName("科長");
        user.setRole(User.Role.SECTION_CHIEF);
        when(userRepository.findByUsername("chief")).thenReturn(Optional.of(user));

        UserDetails details = customUserDetailsService.loadUserByUsername("chief");

        assertThat(details.getUsername()).isEqualTo("chief");
        assertThat(details.getPassword()).isEqualTo("$2b$10$hash");
        assertThat(details.getAuthorities()).extracting("authority").containsExactly("ROLE_SECTION_CHIEF");
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void throwsWhenUserNotFound() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customUserDetailsService.loadUserByUsername("ghost"))
            .isInstanceOf(UsernameNotFoundException.class);
    }
}
