package com.qilu.dto;

import com.qilu.utils.RegexPatterns;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class LoginFormDTO {
    @NotBlank(message = "phone is required")
    @Pattern(regexp = RegexPatterns.PHONE_REGEX, message = "phone is not valid")
    private String phone;

    @Size(max = 6, message = "code length is not valid")
    private String code;

    @Size(max = 32, message = "password length is not valid")
    private String password;
}
