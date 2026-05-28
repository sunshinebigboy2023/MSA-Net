package com.yupi.usercenter.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.usercenter.exception.BusinessException;
import com.yupi.usercenter.mapper.AnalysisTaskMapper;
import com.yupi.usercenter.model.domain.analysis.AnalysisTask;
import com.yupi.usercenter.model.domain.request.AnalysisCallbackRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.Date;

class AnalysisTaskServiceSecurityTest {

    @Test
    void getTaskResponseRequiresMatchingUserId() {
        AnalysisTaskMapper taskMapper = Mockito.mock(AnalysisTaskMapper.class);
        AnalysisCacheService cacheService = Mockito.mock(AnalysisCacheService.class);
        AnalysisTaskService service = new AnalysisTaskService(taskMapper, cacheService, new ObjectMapper());
        Mockito.when(taskMapper.selectOne(Mockito.any())).thenReturn(null);

        Assertions.assertThrows(BusinessException.class, () -> service.getTaskResponse("task-1", 123L));
        Mockito.verify(cacheService, Mockito.never()).getCachedTask(Mockito.anyString());
    }

    @Test
    void completeFromCallbackDoesNotOverrideSuccessWithFailed() throws Exception {
        AnalysisTaskMapper taskMapper = Mockito.mock(AnalysisTaskMapper.class);
        AnalysisCacheService cacheService = Mockito.mock(AnalysisCacheService.class);
        AnalysisTaskService service = new AnalysisTaskService(taskMapper, cacheService, new ObjectMapper());

        AnalysisTask task = buildTask(AnalysisTask.STATUS_SUCCESS);
        task.setResult(new ObjectMapper().writeValueAsString(Collections.singletonMap("message", "ok")));
        Mockito.when(taskMapper.selectOne(Mockito.any())).thenReturn(task);

        AnalysisCallbackRequest request = new AnalysisCallbackRequest();
        request.setTaskId(task.getTaskId());
        request.setStatus(AnalysisTask.STATUS_FAILED);
        request.setError("late failure");

        AnalysisTask result = service.completeFromCallback(request);

        Assertions.assertEquals(AnalysisTask.STATUS_SUCCESS, result.getStatus());
        Mockito.verify(taskMapper, Mockito.never()).update(Mockito.any(), Mockito.any());
        Mockito.verify(cacheService, Mockito.never()).cacheTask(Mockito.any());
    }

    private AnalysisTask buildTask(String status) {
        AnalysisTask task = new AnalysisTask();
        task.setTaskId("task-1");
        task.setUserId(8L);
        task.setStatus(status);
        task.setPayload("{\"videoFile\":\"/tmp/mock.mp4\"}");
        task.setCreateTime(new Date());
        task.setUpdateTime(new Date());
        return task;
    }
}
