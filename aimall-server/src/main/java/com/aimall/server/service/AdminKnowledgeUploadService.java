package com.aimall.server.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface AdminKnowledgeUploadService {
    Map<String, Object> checkUpload(String fileName, Long fileSize, String sha256);

    Map<String, Object> upload(MultipartFile file, Map<String, String> metadata);

    Map<String, Object> uploadVersion(Long docId, MultipartFile file);
}
