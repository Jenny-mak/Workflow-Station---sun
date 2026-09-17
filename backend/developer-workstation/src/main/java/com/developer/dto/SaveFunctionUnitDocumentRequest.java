package com.developer.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 手动保存文档 / 恢复历史版本。
 *
 * <p>{@code baseVersion} 是编辑者打开时看到的版本（还没有文档时为 0）；与当前最新版本不一致即 409。</p>
 */
@Data
public class SaveFunctionUnitDocumentRequest {

    /** 恢复接口不需要正文 */
    private String content;

    @NotNull
    @Min(0)
    private Integer baseVersion;
}
