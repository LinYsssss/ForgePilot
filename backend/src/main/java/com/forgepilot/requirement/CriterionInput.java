package com.forgepilot.requirement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 调用方提交的单条验收条件。{@code acKey} 为 null 表示「新增条件」；
 * 若给了值，它必须已属于本需求，并且会被原样保留——因为它是跨修订
 * 稳定的身份（D011）。
 */
public record CriterionInput(@Size(max = 64) String acKey, @NotBlank String text) {
}
