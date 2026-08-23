package com.forgepilot.requirement;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 调用方提交的某次修订的文本与验收条件。创建需求以及此后每次内容变更都用它；
 * {@code sortOrder} 由数组下标推导，绝不接受客户端传入（api-contract 3）。
 */
public record RequirementContent(
        @NotBlank @Size(max = 200) String title,
        String background,
        String description,
        @NotEmpty @Valid List<CriterionInput> acceptanceCriteria) {
}
