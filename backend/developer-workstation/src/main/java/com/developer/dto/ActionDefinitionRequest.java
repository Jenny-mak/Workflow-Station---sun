package com.developer.dto;

import com.developer.enums.ActionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 动作定义请求DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionDefinitionRequest {
    
    @NotBlank(message = "{validation.action_name_required}")
    @Size(max = 100, message = "{validation.action_name_max_length}")
    private String actionName;
    
    @NotNull(message = "{validation.action_type_required}")
    private ActionType actionType;
    
    @NotNull(message = "{validation.action_config_required}")
    private Map<String, Object> configJson;
    
    private String icon;

    /** User Portal 按钮颜色，`#RRGGBB`；列宽 VARCHAR(20)，超长直接拒绝而不是让 DB 报 500。 */
    @Size(max = 20, message = "{validation.action_button_color_max_length}")
    private String buttonColor;
    private String description;
}
