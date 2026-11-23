package com.kyongseo.banktransfer.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank @Size(max = 50) String ownerName,
        @PositiveOrZero long initialBalance
) {
}
