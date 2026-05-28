package com.yupi.usercenter.service;

import com.yupi.usercenter.model.domain.User;
import com.yupi.usercenter.model.domain.analysis.AnalysisResultResponse;
import com.yupi.usercenter.model.domain.analysis.AnalysisTaskResponse;
import org.springframework.web.multipart.MultipartFile;

public interface AnalysisService {

    AnalysisTaskResponse submit(String text, String language, Boolean enhanceTextWithTranscript, MultipartFile video, User currentUser);

    AnalysisTaskResponse getTask(String taskId, Long userId);

    AnalysisResultResponse getResult(String taskId, Long userId);
}
