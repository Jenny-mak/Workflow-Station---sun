package com.developer.component;

import com.developer.dto.FileTransferResponse;

import java.util.List;

public interface FileTransferComponent {

    List<FileTransferResponse> query(List<String> storedNames);

    FileTransferResponse updateDescription(String storedName, String fileDescription);
}
