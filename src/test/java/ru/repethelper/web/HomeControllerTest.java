package ru.repethelper.web;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HomeControllerTest {
    private final HomeController controller = new HomeController();

    @Test void anonymousVisitorGetsLanding() {
        var anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        assertThat(controller.home(anonymous)).isEqualTo("landing");
        assertThat(controller.home(null)).isEqualTo("landing");
    }

    @Test void authenticatedUsersGoToTheirCabinet() {
        var teacher = UsernamePasswordAuthenticationToken.authenticated("teacher", "",
                List.of(new SimpleGrantedAuthority("ROLE_TEACHER")));
        var student = UsernamePasswordAuthenticationToken.authenticated("student", "",
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
        assertThat(controller.home(teacher)).isEqualTo("redirect:/teacher");
        assertThat(controller.home(student)).isEqualTo("redirect:/student");
    }
}
