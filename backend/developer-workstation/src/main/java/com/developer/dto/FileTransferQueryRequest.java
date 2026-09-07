package com.developer.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileTransferQueryRequest {

    @NotEmpty
    @Size(max = 50)
    private List<@Size(min = 1, max = 150) String> storedNames;
}
