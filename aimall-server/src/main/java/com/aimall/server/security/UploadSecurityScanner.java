package com.aimall.server.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class UploadSecurityScanner {
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final String EICAR_MARKER = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE";
    private static final long MAX_EXPANDED_DOCX_BYTES = 200L * 1024 * 1024;
    private static final int MAX_DOCX_ENTRIES = 5_000;

    private final boolean antivirusEnabled;
    private final boolean antivirusRequired;
    private final String antivirusHost;
    private final int antivirusPort;

    public UploadSecurityScanner(
            @Value("${aimall.security.upload.antivirus.enabled:false}") boolean antivirusEnabled,
            @Value("${aimall.security.upload.antivirus.required:false}") boolean antivirusRequired,
            @Value("${aimall.security.upload.antivirus.host:127.0.0.1}") String antivirusHost,
            @Value("${aimall.security.upload.antivirus.port:3310}") int antivirusPort
    ) {
        this.antivirusEnabled = antivirusEnabled;
        this.antivirusRequired = antivirusRequired;
        this.antivirusHost = antivirusHost;
        this.antivirusPort = antivirusPort;
    }

    public void scan(MultipartFile file, String fileType) {
        validateStructure(file, fileType);
        rejectKnownTestSignature(file);
        if (!antivirusEnabled) {
            if (antivirusRequired) throw new IllegalStateException("上传防病毒服务未启用");
            return;
        }
        scanWithClamAv(file);
    }

    private void validateStructure(MultipartFile file, String fileType) {
        try {
            switch (fileType) {
                case "PDF" -> requireMagic(file, PDF_MAGIC, "PDF 文件签名无效");
                case "DOCX" -> validateDocx(file);
                case "MD", "TXT" -> validateText(file);
                default -> throw new IllegalArgumentException("不支持的上传文件类型");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("上传文件结构校验失败", exception);
        }
    }

    private void requireMagic(MultipartFile file, byte[] magic, String message) throws Exception {
        try (InputStream input = file.getInputStream()) {
            byte[] actual = input.readNBytes(magic.length);
            if (actual.length != magic.length) throw new IllegalArgumentException(message);
            for (int index = 0; index < magic.length; index++) {
                if (actual[index] != magic[index]) throw new IllegalArgumentException(message);
            }
        }
    }

    private void validateDocx(MultipartFile file) throws Exception {
        requireMagic(file, new byte[]{'P', 'K', 3, 4}, "DOCX 文件签名无效");
        Set<String> entries = new HashSet<>();
        long expandedBytes = 0;
        int entryCount = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(file.getInputStream()))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_DOCX_ENTRIES) throw new IllegalArgumentException("DOCX 条目数量异常");
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) {
                    throw new IllegalArgumentException("DOCX 包含非法路径");
                }
                if (name.toLowerCase().contains("vbaproject.bin")) {
                    throw new IllegalArgumentException("不允许上传包含宏的 Office 文档");
                }
                entries.add(name);
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expandedBytes += read;
                    if (expandedBytes > MAX_EXPANDED_DOCX_BYTES) {
                        throw new IllegalArgumentException("DOCX 展开大小超过安全限制");
                    }
                }
            }
        }
        if (!entries.contains("[Content_Types].xml") || !entries.contains("word/document.xml")) {
            throw new IllegalArgumentException("DOCX 缺少必要文档结构");
        }
    }

    private void validateText(MultipartFile file) throws Exception {
        byte[] bytes = file.getBytes();
        for (byte value : bytes) {
            if (value == 0) throw new IllegalArgumentException("文本文件包含非法 NUL 字节");
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("文本文件必须使用 UTF-8 编码", exception);
        }
    }

    private void rejectKnownTestSignature(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            byte[] marker = EICAR_MARKER.getBytes(StandardCharsets.US_ASCII);
            byte[] window = input.readNBytes(8192);
            if (new String(window, StandardCharsets.ISO_8859_1).contains(EICAR_MARKER)) {
                throw new IllegalArgumentException("上传文件被安全扫描拒绝");
            }
            if (marker.length == 0) throw new IllegalStateException("invalid marker");
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("上传文件安全扫描失败", exception);
        }
    }

    private void scanWithClamAv(MultipartFile file) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(antivirusHost, antivirusPort), 5_000);
            socket.setSoTimeout(15_000);
            try (DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                 InputStream input = file.getInputStream()) {
                output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.writeInt(read);
                    output.write(buffer, 0, read);
                }
                output.writeInt(0);
                output.flush();
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                socket.getInputStream().transferTo(response);
                String result = response.toString(StandardCharsets.US_ASCII);
                if (!result.contains("OK") || result.contains("FOUND")) {
                    throw new IllegalArgumentException("上传文件被防病毒服务拒绝");
                }
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("上传防病毒服务不可用", exception);
        }
    }
}
