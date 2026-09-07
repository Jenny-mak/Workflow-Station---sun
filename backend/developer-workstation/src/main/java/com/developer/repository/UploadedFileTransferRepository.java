package com.developer.repository;

import com.developer.entity.UploadedFileTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UploadedFileTransferRepository extends JpaRepository<UploadedFileTransfer, Long> {

    Optional<UploadedFileTransfer> findByStoredName(String storedName);

    List<UploadedFileTransfer> findByStoredNameIn(Collection<String> storedNames);
}
