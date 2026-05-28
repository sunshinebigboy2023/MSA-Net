package com.yupi.usercenter.service.impl;

import com.yupi.usercenter.common.ErrorCode;
import com.yupi.usercenter.config.MsaProperties;
import com.yupi.usercenter.exception.BusinessException;
import com.yupi.usercenter.model.domain.User;
import com.yupi.usercenter.model.domain.analysis.AnalysisResultResponse;
import com.yupi.usercenter.model.domain.analysis.AnalysisTask;
import com.yupi.usercenter.model.domain.analysis.AnalysisTaskMessage;
import com.yupi.usercenter.model.domain.analysis.AnalysisTaskResponse;
import com.yupi.usercenter.service.AnalysisService;
import com.yupi.usercenter.service.MsaClient;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AnalysisServiceImpl implements AnalysisService {

    private static final Set<String> ALLOWED_VIDEO_EXTENSIONS = new HashSet<>(
            Arrays.asList(".mp4", ".mov", ".avi", ".mkv"));

    private static final Set<String> ALLOWED_VIDEO_MIME_TYPES = new HashSet<>(
            Arrays.asList("video/mp4", "video/quicktime", "video/x-msvideo", "video/x-matroska"));

    private static final List<String> OCTET_STREAM_MIME_TYPES = Arrays.asList("application/octet-stream");

    private final MsaClient msaClient;

    private final MsaProperties properties;

    public AnalysisServiceImpl(MsaClient msaClient, MsaProperties properties) {
        this(msaClient, properties, null, null, null, null);
    }

    @Autowired
    public AnalysisServiceImpl(
            MsaClient msaClient,
            MsaProperties properties,
            AnalysisTaskService taskService,
            AnalysisQueueProducer queueProducer,
            AnalysisRateLimitService rateLimitService,
            AnalysisCacheService cacheService) {
        this.msaClient = msaClient;
        this.properties = properties;
        this.taskService = taskService;
        this.queueProducer = queueProducer;
        this.rateLimitService = rateLimitService;
        this.cacheService = cacheService;
    }

    private final AnalysisTaskService taskService;

    private final AnalysisQueueProducer queueProducer;

    private final AnalysisRateLimitService rateLimitService;

    private final AnalysisCacheService cacheService;

    @Override
    public AnalysisTaskResponse submit(
            String text,
            String language,
            Boolean enhanceTextWithTranscript,
            MultipartFile video,
            User currentUser) {
        validateVideo(video);
        boolean hasText = StringUtils.isNotBlank(text);
        boolean hasVideo = video != null;
        if (!hasText && !hasVideo) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Please enter text or upload a video");
        }

        Map<String, Object> payload = new HashMap<>();
        if (hasText) {
            payload.put("text", text.trim());
        }

        String normalizedLanguage = normalizeLanguage(language);
        if (StringUtils.isNotBlank(normalizedLanguage)) {
            payload.put("language", normalizedLanguage);
        }

        if (hasVideo) {
            payload.put("videoFile", saveVideo(video, currentUser).toAbsolutePath().toString());
            if (Boolean.TRUE.equals(enhanceTextWithTranscript) && hasText) {
                payload.put("enhanceTextWithTranscript", true);
            }
        }

        if (useAsyncQueue()) {
            long userId = currentUser == null || currentUser.getId() == null ? 0L : currentUser.getId();
            if (!rateLimitService.allowSubmit(userId)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "Analysis submit rate limit exceeded");
            }
            AnalysisTask task = taskService.createQueuedTask(userId, payload);
            cacheService.cacheTask(task);
            queueProducer.publish(new AnalysisTaskMessage(task.getTaskId(), userId, payload, task.getRetryCount()));
            return AnalysisCacheService.toTaskResponse(task);
        }

        return msaClient.submitAnalysis(payload);
    }

    @Override
    public AnalysisTaskResponse getTask(String taskId, Long userId) {
        if (useAsyncQueue()) {
            return taskService.getTaskResponse(taskId, userId);
        }
        return msaClient.getTask(taskId);
    }

    @Override
    public AnalysisResultResponse getResult(String taskId, Long userId) {
        if (useAsyncQueue()) {
            return taskService.getResultResponse(taskId, userId);
        }
        return msaClient.getResult(taskId);
    }

    private boolean useAsyncQueue() {
        return Boolean.TRUE.equals(properties.getAsyncEnabled())
                && taskService != null
                && queueProducer != null
                && rateLimitService != null
                && cacheService != null;
    }

    private String normalizeLanguage(String language) {
        if (StringUtils.isBlank(language)) {
            return null;
        }
        String value = language.trim().toLowerCase(Locale.ROOT);
        if ("zh".equals(value) || "en".equals(value)) {
            return value;
        }
        return null;
    }

    private void validateVideo(MultipartFile video) {
        if (video == null) {
            return;
        }
        if (video.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Uploaded video must not be empty");
        }
        long maxVideoSizeBytes = properties.getMaxVideoSizeMb() * 1024L * 1024L;
        if (video.getSize() > maxVideoSizeBytes) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "Uploaded video exceeds the size limit of " + properties.getMaxVideoSizeMb() + "MB");
        }

        String extension = extensionOf(video.getOriginalFilename());
        if (!ALLOWED_VIDEO_EXTENSIONS.contains(extension)) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "Unsupported video extension. Allowed extensions: .mp4, .mov, .avi, .mkv");
        }

        String mimeType = StringUtils.trimToEmpty(video.getContentType()).toLowerCase(Locale.ROOT);
        if (ALLOWED_VIDEO_MIME_TYPES.contains(mimeType)) {
            return;
        }
        if (OCTET_STREAM_MIME_TYPES.contains(mimeType)) {
            return;
        }
        throw new BusinessException(
                ErrorCode.PARAMS_ERROR,
                "Unsupported video MIME type. Allowed MIME types: video/mp4, video/quicktime, video/x-msvideo, video/x-matroska");
    }

    private Path saveVideo(MultipartFile video, User currentUser) {
        long userId = currentUser == null || currentUser.getId() == null ? 0L : currentUser.getId();
        Path uploadDir = Paths.get(properties.getUploadDir(), String.valueOf(userId)).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadDir);
            Path target = uploadDir.resolve(UUID.randomUUID() + extensionOf(video.getOriginalFilename()));
            try (InputStream inputStream = video.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存上传视频失败，请检查上传目录权限和磁盘空间");
        }
    }

    private String extensionOf(String filename) {
        if (StringUtils.isBlank(filename)) {
            return ".mp4";
        }
        String cleanName = Paths.get(filename).getFileName().toString();
        int dotIndex = cleanName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == cleanName.length() - 1) {
            return ".mp4";
        }
        String extension = cleanName.substring(dotIndex).toLowerCase(Locale.ROOT);
        if (extension.length() > 10) {
            return ".mp4";
        }
        return extension;
    }
}
