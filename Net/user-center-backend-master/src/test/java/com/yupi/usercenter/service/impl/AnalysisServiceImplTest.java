package com.yupi.usercenter.service.impl;

import com.yupi.usercenter.config.MsaProperties;
import com.yupi.usercenter.exception.BusinessException;
import com.yupi.usercenter.model.domain.User;
import com.yupi.usercenter.model.domain.analysis.AnalysisResultResponse;
import com.yupi.usercenter.model.domain.analysis.AnalysisTaskResponse;
import com.yupi.usercenter.service.MsaClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Map;

class AnalysisServiceImplTest {

    @Test
    void submitTextOnlyForwardsTextAndLanguage() {
        CapturingClient client = new CapturingClient();
        AnalysisServiceImpl service = new AnalysisServiceImpl(client, new MsaProperties());
        User user = new User();
        user.setId(7L);

        AnalysisTaskResponse response = service.submit("happy", "en", false, null, user);

        Assertions.assertEquals("task-1", response.getTaskId());
        Assertions.assertEquals("happy", client.payload.get("text"));
        Assertions.assertEquals("en", client.payload.get("language"));
    }

    @Test
    void submitWithoutTextOrVideoFails() {
        AnalysisServiceImpl service = new AnalysisServiceImpl(new CapturingClient(), new MsaProperties());
        User user = new User();
        user.setId(7L);

        Assertions.assertThrows(BusinessException.class, () -> service.submit(" ", "auto", false, null, user));
    }

    @Test
    void submitVideoSavesFileAndForwardsAbsolutePath() {
        CapturingClient client = new CapturingClient();
        MsaProperties properties = new MsaProperties();
        properties.setUploadDir("target/test-uploads");
        AnalysisServiceImpl service = new AnalysisServiceImpl(client, properties);
        User user = new User();
        user.setId(9L);
        MockMultipartFile video = new MockMultipartFile("video", "sample.mp4", "video/mp4", new byte[]{1, 2, 3});

        service.submit("", "en", false, video, user);

        String videoFile = String.valueOf(client.payload.get("videoFile"));
        Assertions.assertTrue(videoFile.endsWith(".mp4"));
        Assertions.assertTrue(new File(videoFile).exists());
        Assertions.assertEquals("en", client.payload.get("language"));
    }

    @Test
    void submitTextAndVideoForwardsTranscriptEnhancementFlag() {
        CapturingClient client = new CapturingClient();
        MsaProperties properties = new MsaProperties();
        properties.setUploadDir("target/test-uploads-enhance");
        AnalysisServiceImpl service = new AnalysisServiceImpl(client, properties);
        User user = new User();
        user.setId(11L);
        MockMultipartFile video = new MockMultipartFile("video", "sample.mp4", "video/mp4", new byte[]{1, 2, 3});

        service.submit("caption", "en", true, video, user);

        Assertions.assertEquals("caption", client.payload.get("text"));
        Assertions.assertEquals(Boolean.TRUE, client.payload.get("enhanceTextWithTranscript"));
    }

    @Test
    void submitRejectsUnsupportedExtension() {
        CapturingClient client = new CapturingClient();
        MsaProperties properties = new MsaProperties();
        properties.setUploadDir("target/test-uploads-invalid-extension");
        AnalysisServiceImpl service = new AnalysisServiceImpl(client, properties);
        User user = new User();
        user.setId(12L);
        MockMultipartFile video = new MockMultipartFile("video", "sample.exe", "video/mp4", new byte[]{1, 2, 3});

        Assertions.assertThrows(BusinessException.class, () -> service.submit("", "en", false, video, user));
    }

    @Test
    void submitRejectsUnsupportedMimeType() {
        CapturingClient client = new CapturingClient();
        MsaProperties properties = new MsaProperties();
        properties.setUploadDir("target/test-uploads-invalid-mime");
        AnalysisServiceImpl service = new AnalysisServiceImpl(client, properties);
        User user = new User();
        user.setId(13L);
        MockMultipartFile video = new MockMultipartFile("video", "sample.mp4", "image/png", new byte[]{1, 2, 3});

        Assertions.assertThrows(BusinessException.class, () -> service.submit("", "en", false, video, user));
    }

    @Test
    void submitRejectsVideoLargerThanConfiguredLimit() {
        CapturingClient client = new CapturingClient();
        MsaProperties properties = new MsaProperties();
        properties.setUploadDir("target/test-uploads-too-large");
        properties.setMaxVideoSizeMb(1L);
        AnalysisServiceImpl service = new AnalysisServiceImpl(client, properties);
        User user = new User();
        user.setId(14L);
        byte[] content = new byte[1024 * 1024 + 1];
        MockMultipartFile video = new MockMultipartFile("video", "sample.mp4", "video/mp4", content);

        Assertions.assertThrows(BusinessException.class, () -> service.submit("", "en", false, video, user));
    }

    @Test
    void submitVideoFallsBackToInputStreamWhenTransferToFails() throws Exception {
        CapturingClient client = new CapturingClient();
        MsaProperties properties = new MsaProperties();
        properties.setUploadDir("target/test-uploads-transfer-fallback");
        AnalysisServiceImpl service = new AnalysisServiceImpl(client, properties);
        User user = new User();
        user.setId(10L);
        MultipartFile video = new TransferToFailingMultipartFile("sample.mp4", new byte[]{4, 5, 6});

        service.submit("", "zh", false, video, user);

        String videoFile = String.valueOf(client.payload.get("videoFile"));
        Assertions.assertTrue(new File(videoFile).isAbsolute());
        Assertions.assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(new File(videoFile).toPath()));
        Assertions.assertEquals("zh", client.payload.get("language"));
    }

    private static class CapturingClient implements MsaClient {

        private Map<String, Object> payload;

        @Override
        public AnalysisTaskResponse submitAnalysis(Map<String, Object> payload) {
            this.payload = payload;
            AnalysisTaskResponse response = new AnalysisTaskResponse();
            response.setTaskId("task-1");
            response.setStatus("PENDING");
            return response;
        }

        @Override
        public AnalysisTaskResponse getTask(String taskId) {
            return null;
        }

        @Override
        public AnalysisResultResponse getResult(String taskId) {
            return null;
        }
    }

    private static class TransferToFailingMultipartFile implements MultipartFile {

        private final String originalFilename;

        private final byte[] content;

        private TransferToFailingMultipartFile(String originalFilename, byte[] content) {
            this.originalFilename = originalFilename;
            this.content = content;
        }

        @Override
        public String getName() {
            return "video";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return "video/mp4";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            throw new IOException("simulated transfer failure");
        }
    }
}
