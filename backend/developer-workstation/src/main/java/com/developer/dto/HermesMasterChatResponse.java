package com.developer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Hermes Master 单轮回复（Markdown 文本）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HermesMasterChatResponse {

    private String reply;
}
