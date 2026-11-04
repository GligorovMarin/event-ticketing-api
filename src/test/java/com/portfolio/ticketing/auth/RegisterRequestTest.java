package com.portfolio.ticketing.auth;

import com.portfolio.ticketing.auth.dto.RegisterRequest;
import com.portfolio.ticketing.user.UserRole;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UNIT test of the validation rules written as annotations on {@link RegisterRequest}.
 * We run the real Bean Validation engine on plain Java objects: no web server needed.
 */
class RegisterRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<ConstraintViolation<RegisterRequest>> validate(String email, String name, String password, UserRole role) {
        return VALIDATOR.validate(new RegisterRequest(email, name, password, role));
    }

    @Test
    @DisplayName("a normal request is valid")
    void validRequest() {
        assertThat(validate("ana@example.com", "Ana", "long-enough-pw", UserRole.CUSTOMER)).isEmpty();
    }

    @Test
    @DisplayName("the e-mail is trimmed and lower-cased as soon as the record is created")
    void emailIsNormalised() {
        RegisterRequest request = new RegisterRequest("  Ana@Example.COM  ", "Ana", "long-enough-pw", UserRole.CUSTOMER);

        assertThat(request.email()).isEqualTo("ana@example.com");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            not an e-mail             | not-an-email      | Ana | long-enough-pw
            blank e-mail              | '   '            | Ana | long-enough-pw
            blank name                | ana@example.com   | ' ' | long-enough-pw
            password of 7 characters  | ana@example.com   | Ana | 1234567
            """)
    @DisplayName("invalid fields are reported")
    void invalidFields(String description, String email, String name, String password) {
        assertThat(validate(email, name, password, UserRole.CUSTOMER)).as(description).isNotEmpty();
    }

    @Test
    @DisplayName("a password of 72 characters is allowed, 73 is not")
    void passwordLengthBoundary() {
        assertThat(validate("ana@example.com", "Ana", "x".repeat(72), UserRole.CUSTOMER)).isEmpty();
        assertThat(validate("ana@example.com", "Ana", "x".repeat(73), UserRole.CUSTOMER)).isNotEmpty();
    }

    @Test
    @DisplayName("the role is required")
    void roleIsRequired() {
        assertThat(validate("ana@example.com", "Ana", "long-enough-pw", null)).isNotEmpty();
    }
}
