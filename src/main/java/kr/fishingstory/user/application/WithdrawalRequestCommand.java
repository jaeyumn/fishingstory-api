package kr.fishingstory.user.application;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record WithdrawalRequestCommand(@NotNull @AssertTrue Boolean confirmed) { }
