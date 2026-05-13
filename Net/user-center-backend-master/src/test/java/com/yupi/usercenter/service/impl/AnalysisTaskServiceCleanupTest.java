package com.yupi.usercenter.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.usercenter.mapper.AnalysisTaskMapper;
import com.yupi.usercenter.model.domain.analysis.AnalysisTask;
import com.yupi.usercenter.model.domain.request.AnalysisCallbackRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Date;

class AnalysisTaskServiceCleanupTest {

    @Test
    void completeFromCallbackDeletesUploadedVideoForSuccess() throws Exception {
        AnalysisTaskMapper taskMapper = Mockito.mock(AnalysisTaskMapper.class);
        AnalysisCacheService cacheService = Mockito.mock(AnalysisCacheService.class);
        AnalysisTaskService service = new AnalysisTaskService(taskMapper, cacheService, new ObjectMapper());

        Path upload = Files.createTempFile("msa-upload-success", ".mp4");
        AnalysisTask task = buildTask(upload);
        Mockito.when(taskMapper.selectOne(Mockito.any())).thenReturn(task);

        AnalysisCallbackRequest request = new AnalysisCallbackRequest();
        request.setTaskId(task.getTaskId());
        request.setStatus(AnalysisTask.STATUS_SUCCESS);
        request.setResult(Collections.singletonMap("message", "ok"));

        service.completeFromCallback(request);

        Assertions.assertFalse(Files.exists(upload));
        Mockito.verify(cacheService).cacheTask(Mockito.any(AnalysisTask.class));
        Mockito.verify(cacheService).cacheResult(Mockito.eq(task.getTaskId()), Mockito.any());
        ArgumentCaptor<AnalysisTask> taskCaptor = ArgumentCaptor.forClass(AnalysisTask.class);
        Mockito.verify(taskMapper).update(taskCaptor.capture(), Mockito.any());
        Assertions.assertEquals(AnalysisTask.STATUS_SUCCESS, taskCaptor.getValue().getStatus());
    }

    @Test
    void completeFromCallbackKeepsUploadedVideoForRetrying() throws Exception {
        AnalysisTaskMapper taskMapper = Mockito.mock(AnalysisTaskMapper.class);
        AnalysisCacheService cacheService = Mockito.mock(AnalysisCacheService.class);
        AnalysisTaskService service = new AnalysisTaskService(taskMapper, cacheService, new ObjectMapper());

        Path upload = Files.createTempFile("msa-upload-retrying", ".mp4");
        AnalysisTask task = buildTask(upload);
        Mockito.when(taskMapper.selectOne(Mockito.any())).thenReturn(task);

        AnalysisCallbackRequest request = new AnalysisCallbackRequest();
        request.setTaskId(task.getTaskId());
        request.setStatus(AnalysisTask.STATUS_RETRYING);
        request.setError("temporary failure");

        service.completeFromCallback(request);

        Assertions.assertTrue(Files.exists(upload));
        Files.deleteIfExists(upload);
        Mockito.verify(cacheService).cacheTask(Mockito.any(AnalysisTask.class));
        Mockito.verify(cacheService, Mockito.never()).cacheResult(Mockito.anyString(), Mockito.any());
    }

    private AnalysisTask buildTask(Path upload) throws Exception {
        AnalysisTask task = new AnalysisTask();
        task.setTaskId("task-1");
        task.setStatus(AnalysisTask.STATUS_QUEUED);
        task.setPayload(new ObjectMapper().writeValueAsString(
                Collections.singletonMap("videoFile", upload.toAbsolutePath().toString())));
        task.setCreateTime(new Date());
        task.setUpdateTime(new Date());
        return task;
    }
}
