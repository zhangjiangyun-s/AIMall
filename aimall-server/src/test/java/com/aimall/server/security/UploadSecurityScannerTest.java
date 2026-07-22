package com.aimall.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadSecurityScannerTest {
    private final UploadSecurityScanner scanner = new UploadSecurityScanner(false, false, "127.0.0.1", 3310);

    @Test
    void acceptsValidPdfTextAndDocxStructures() throws Exception {
        assertDoesNotThrow(() -> scanner.scan(file("policy.pdf", "%PDF-1.7\nbody"), "PDF"));
        assertDoesNotThrow(() -> scanner.scan(file("policy.txt", "安全 UTF-8 文本"), "TXT"));
        MockMultipartFile docx = new MockMultipartFile(
                "file", "policy.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                minimalDocx()
        );
        assertDoesNotThrow(() -> scanner.scan(docx, "DOCX"));
    }

    @Test
    void rejectsExtensionSpoofingInvalidTextAndEicar() {
        assertThrows(IllegalArgumentException.class,
                () -> scanner.scan(file("fake.pdf", "not a pdf"), "PDF"));
        assertThrows(IllegalArgumentException.class,
                () -> scanner.scan(new MockMultipartFile("file", "bad.txt", "text/plain", new byte[]{'a', 0, 'b'}), "TXT"));
        assertThrows(IllegalArgumentException.class,
                () -> scanner.scan(file("eicar.txt", "EICAR-STANDARD-ANTIVIRUS-TEST-FILE"), "TXT"));
    }

    @Test
    void failsClosedWhenAntivirusIsRequiredButDisabled() {
        UploadSecurityScanner required = new UploadSecurityScanner(false, true, "127.0.0.1", 3310);
        assertThrows(IllegalStateException.class,
                () -> required.scan(file("policy.txt", "content"), "TXT"));
    }

    private MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] minimalDocx() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<document/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
