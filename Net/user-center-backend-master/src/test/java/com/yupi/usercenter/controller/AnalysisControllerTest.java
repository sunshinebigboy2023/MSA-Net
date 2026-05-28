package com.yupi.usercenter.controller;

import com.yupi.usercenter.model.domain.User;
import com.yupi.usercenter.model.domain.analysis.AnalysisTaskResponse;
import com.yupi.usercenter.service.AnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.yupi.usercenter.contant.UserConstant.USER_LOGIN_STATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalysisControllerTest {

    private MockMvc mockMvc;

    private AnalysisService analysisService;

    @BeforeEach
    void setUp() {
        analysisService = Mockito.mock(AnalysisService.class);
        AnalysisController controller = new AnalysisController();
        ReflectionTestUtils.setField(controller, "analysisService", analysisService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void analyzeRequiresLoginAndReturnsTask() throws Exception {
        User user = new User();
        user.setId(1L);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(USER_LOGIN_STATE, user);

        AnalysisTaskResponse response = new AnalysisTaskResponse();
        response.setTaskId("task-1");
        response.setStatus("PENDING");
        Mockito.when(analysisService.submit(
                        Mockito.eq("hello"),
                        Mockito.eq("en"),
                        Mockito.eq(true),
                        Mockito.isNull(),
                        Mockito.eq(user)))
                .thenReturn(response);

        mockMvc.perform(multipart("/analysis/analyze")
                        .param("text", "hello")
                        .param("language", "en")
                        .param("enhanceTextWithTranscript", "true")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("task-1"));
    }

    @Test
    void getTaskUsesCurrentUserId() throws Exception {
        User user = new User();
        user.setId(9L);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(USER_LOGIN_STATE, user);

        AnalysisTaskResponse response = new AnalysisTaskResponse();
        response.setTaskId("task-99");
        response.setStatus("QUEUED");
        Mockito.when(analysisService.getTask("task-99", 9L)).thenReturn(response);

        mockMvc.perform(get("/analysis/task/task-99").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("task-99"));
    }
}
