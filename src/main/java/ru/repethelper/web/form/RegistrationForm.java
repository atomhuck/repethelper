package ru.repethelper.web.form;

import jakarta.validation.constraints.*;
import ru.repethelper.domain.Role;
import java.nio.charset.StandardCharsets;

public class RegistrationForm {
    @NotBlank(message = "Введите имя и фамилию")
    @Size(min = 2, max = 80, message = "Имя должно содержать от 2 до 80 символов")
    private String displayName;
    @NotBlank(message = "Введите email") @Email(message = "Введите корректный email")
    @Size(max = 254, message = "Email слишком длинный")
    private String email;
    @NotNull(message = "Выберите тип аккаунта")
    private Role role = Role.STUDENT;
    @NotBlank(message = "Введите пароль")
    private String password;
    @NotBlank(message = "Повторите пароль")
    private String passwordConfirmation;
    @AssertTrue(message = "Необходимо принять пользовательское соглашение")
    private boolean termsAccepted;
    @AssertTrue(message = "Необходимо дать согласие на обработку персональных данных")
    private boolean personalDataAccepted;

    @AssertTrue(message = "Пароли не совпадают")
    public boolean isPasswordConfirmationValid() {
        return password != null && password.equals(passwordConfirmation);
    }
    @AssertTrue(message = "Пароль должен содержать не менее 10 символов и не более 72 байт")
    public boolean isPasswordLengthValid() {
        return password != null && password.length() >= 10
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getPasswordConfirmation() { return passwordConfirmation; }
    public void setPasswordConfirmation(String passwordConfirmation) { this.passwordConfirmation = passwordConfirmation; }
    public boolean isTermsAccepted() { return termsAccepted; }
    public void setTermsAccepted(boolean termsAccepted) { this.termsAccepted = termsAccepted; }
    public boolean isPersonalDataAccepted() { return personalDataAccepted; }
    public void setPersonalDataAccepted(boolean personalDataAccepted) { this.personalDataAccepted = personalDataAccepted; }
}
