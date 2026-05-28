package com.yupi.usercenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.usercenter.common.ErrorCode;
import com.yupi.usercenter.exception.BusinessException;
import com.yupi.usercenter.mapper.AnalysisTaskMapper;
import com.yupi.usercenter.model.domain.analysis.AnalysisResultResponse;
import com.yupi.usercenter.model.domain.analysis.AnalysisTask;
import com.yupi.usercenter.model.domain.analysis.AnalysisTaskResponse;
import com.yupi.usercenter.model.domain.request.AnalysisCallbackRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AnalysisTaskService {

    private static final Set<String> TERMINAL_STATUSES = new HashSet<>(
            Arrays.asList(AnalysisTask.STATUS_SUCCESS, AnalysisTask.STATUS_FAILED, AnalysisTask.STATUS_DEAD_LETTER));

    private final AnalysisTaskMapper taskMapper;

    private final AnalysisCacheService cacheService;

    private final ObjectMapper objectMapper;

    public AnalysisTaskService(AnalysisTaskMapper taskMapper, AnalysisCacheService cacheService, ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
    }

    public AnalysisTask createQueuedTask(Long userId, Map<String, Object> payload) {
        AnalysisTask task = new AnalysisTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setUserId(userId == null ? 0L : userId);
        task.setStatus(AnalysisTask.STATUS_QUEUED);
        task.setPayload(toJson(payload));
        task.setRetryCount(0);
        task.setCreateTime(new Date());
        task.setUpdateTime(new Date());
        taskMapper.insert(task);
        return task;
    }

    public AnalysisTask getByTaskId(String taskId) {
        return taskMapper.selectOne(new QueryWrapper<AnalysisTask>().eq("taskId", taskId).last("limit 1"));
    }

    public AnalysisTask getByTaskIdAndUserId(String taskId, Long userId) {
        return taskMapper.selectOne(new QueryWrapper<AnalysisTask>()
                .eq("taskId", taskId)
                .eq("userId", userId)
                .last("limit 1"));
    }

    public AnalysisTaskResponse getTaskResponse(String taskId, Long userId) {
        AnalysisTask task = requireOwnedTask(taskId, userId);
        AnalysisTaskResponse cached = cacheService.getCachedTask(taskId);
        if (cached != null) {
            return cached;
        }
        AnalysisTaskResponse response = AnalysisCacheService.toTaskResponse(task);
        cacheService.cacheTask(task);
        return response;
    }

    public AnalysisResultResponse getResultResponse(String taskId, Long userId) {
        AnalysisTask task = requireOwnedTask(taskId, userId);
        AnalysisResultResponse cached = cacheService.getCachedResult(taskId);
        if (cached != null) {
            return cached;
        }
        if (task.getResult() == null) {
            AnalysisResultResponse response = new AnalysisResultResponse();
            response.setTaskId(task.getTaskId());
            response.setStatus(task.getStatus());
            response.setError(task.getError());
            return response;
        }
        try {
            AnalysisResultResponse response = objectMapper.readValue(task.getResult(), AnalysisResultResponse.class);
            cacheService.cacheResult(taskId, response);
            return response;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Analysis result is not valid JSON");
        }
    }

    public AnalysisTask completeFromCallback(AnalysisCallbackRequest request) {
        AnalysisTask task = requireTask(request.getTaskId());
        String currentStatus = task.getStatus();
        String nextStatus = request.getStatus();
        if (isTerminalStatus(currentStatus)) {
            // Long-running async tasks may receive duplicate, delayed, or stale callbacks.
            // Terminal states must stay immutable so an old callback cannot overwrite the final outcome.
            return task;
        }
        if (!isAllowedCallbackTransition(currentStatus, nextStatus)) {
            return task;
        }
        task.setStatus(request.getStatus());
        task.setError(request.getError());
        task.setProcessingTimeMs(request.getProcessingTimeMs());
        task.setUpdateTime(new Date());
        if (request.getResult() != null) {
            task.setResult(toJson(request.getResult()));
        }
        taskMapper.update(task, new QueryWrapper<AnalysisTask>().eq("taskId", task.getTaskId()));
        cacheService.cacheTask(task);
        if (AnalysisTask.STATUS_SUCCESS.equals(task.getStatus())) {
            cacheService.cacheResult(task.getTaskId(), mapToResult(task.getTaskId(), task.getStatus(), request.getResult(), request.getProcessingTimeMs()));
        }
        cleanupUploadedVideoIfTerminal(task);
        return task;
    }

    public void markRunning(String taskId) {
        AnalysisTask task = requireTask(taskId);
        if (isTerminalStatus(task.getStatus())) {
            return;
        }
        task.setStatus(AnalysisTask.STATUS_RUNNING);
        task.setUpdateTime(new Date());
        taskMapper.update(task, new QueryWrapper<AnalysisTask>().eq("taskId", taskId));
        cacheService.cacheTask(task);
    }

    public void markFailed(String taskId, String status, String error) {
        AnalysisTask task = requireTask(taskId);
        if (isTerminalStatus(task.getStatus())) {
            return;
        }
        task.setStatus(status);
        task.setError(error);
        task.setUpdateTime(new Date());
        taskMapper.update(task, new QueryWrapper<AnalysisTask>().eq("taskId", taskId));
        cacheService.cacheTask(task);
    }

    public void incrementRetry(String taskId) {
        AnalysisTask task = requireTask(taskId);
        if (isTerminalStatus(task.getStatus())) {
            return;
        }
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        task.setRetryCount(retryCount + 1);
        task.setStatus(AnalysisTask.STATUS_RETRYING);
        task.setUpdateTime(new Date());
        taskMapper.update(task, new QueryWrapper<AnalysisTask>().eq("taskId", taskId));
        cacheService.cacheTask(task);
    }

    public List<AnalysisTask> listStaleTasks(Date before, int limit) {
        return taskMapper.selectList(new QueryWrapper<AnalysisTask>()
                .in("status", Arrays.asList(AnalysisTask.STATUS_QUEUED, AnalysisTask.STATUS_RUNNING, AnalysisTask.STATUS_RETRYING))
                .lt("updateTime", before)
                .last("limit " + limit));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> payloadAsMap(AnalysisTask task) {
        try {
            return objectMapper.readValue(task.getPayload(), Map.class);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Analysis task payload is not valid JSON");
        }
    }

    private AnalysisTask requireTask(String taskId) {
        AnalysisTask task = getByTaskId(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Analysis task not found");
        }
        return task;
    }

    private AnalysisTask requireOwnedTask(String taskId, Long userId) {
        AnalysisTask task = getByTaskIdAndUserId(taskId, userId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NO_AUTH, "Analysis task not found or not accessible");
        }
        return task;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to serialize analysis task");
        }
    }

    private AnalysisResultResponse mapToResult(String taskId, String status, Map<String, Object> result, Long processingTimeMs) {
        AnalysisResultResponse response = objectMapper.convertValue(result, AnalysisResultResponse.class);
        response.setTaskId(taskId);
        response.setStatus(status);
        response.setProcessingTimeMs(processingTimeMs);
        return response;
    }

    private void cleanupUploadedVideoIfTerminal(AnalysisTask task) {
        if (!isTerminalStatus(task.getStatus())) {
            return;
        }
        Object videoFile = payloadAsMap(task).get("videoFile");
        if (!(videoFile instanceof String)) {
            return;
        }
        String pathValue = ((String) videoFile).trim();
        if (pathValue.isEmpty()) {
            return;
        }
        Path path = Paths.get(pathValue);
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private boolean isTerminalStatus(String status) {
        return TERMINAL_STATUSES.contains(status);
    }

    private boolean isAllowedCallbackTransition(String currentStatus, String nextStatus) {
        if (AnalysisTask.STATUS_RUNNING.equals(nextStatus)) {
            return AnalysisTask.STATUS_QUEUED.equals(currentStatus)
                    || AnalysisTask.STATUS_RETRYING.equals(currentStatus);
        }
        if (AnalysisTask.STATUS_SUCCESS.equals(nextStatus)) {
            return AnalysisTask.STATUS_RUNNING.equals(currentStatus)
                    || AnalysisTask.STATUS_QUEUED.equals(currentStatus)
                    || AnalysisTask.STATUS_RETRYING.equals(currentStatus);
        }
        if (AnalysisTask.STATUS_FAILED.equals(nextStatus)) {
            return AnalysisTask.STATUS_RUNNING.equals(currentStatus)
                    || AnalysisTask.STATUS_QUEUED.equals(currentStatus)
                    || AnalysisTask.STATUS_RETRYING.equals(currentStatus);
        }
        if (AnalysisTask.STATUS_RETRYING.equals(nextStatus)) {
            return AnalysisTask.STATUS_QUEUED.equals(currentStatus)
                    || AnalysisTask.STATUS_RUNNING.equals(currentStatus);
        }
        return false;
    }
}
