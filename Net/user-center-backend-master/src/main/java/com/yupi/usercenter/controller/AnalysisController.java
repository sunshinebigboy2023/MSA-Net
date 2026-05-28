package com.yupi.usercenter.controller;

import com.yupi.usercenter.common.BaseResponse;
import com.yupi.usercenter.common.ErrorCode;
import com.yupi.usercenter.common.ResultUtils;
import com.yupi.usercenter.exception.BusinessException;
import com.yupi.usercenter.model.domain.User;
import com.yupi.usercenter.model.domain.analysis.AnalysisResultResponse;
import com.yupi.usercenter.model.domain.analysis.AnalysisTaskResponse;
import com.yupi.usercenter.service.AnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

import static com.yupi.usercenter.contant.UserConstant.USER_LOGIN_STATE;

@RestController
@RequestMapping("/analysis")
public class AnalysisController {

    @Resource
    private AnalysisService analysisService;

    @PostMapping("/analyze")
    public BaseResponse<AnalysisTaskResponse> analyze(
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "enhanceTextWithTranscript", required = false) Boolean enhanceTextWithTranscript,
            @RequestPart(value = "video", required = false) MultipartFile video,
            HttpServletRequest request) {
        User currentUser = requireLogin(request);
        return ResultUtils.success(analysisService.submit(text, language, enhanceTextWithTranscript, video, currentUser));
    }

    @GetMapping("/task/{taskId}")
    public BaseResponse<AnalysisTaskResponse> getTask(@PathVariable String taskId, HttpServletRequest request) {
        User currentUser = requireLogin(request);
        return ResultUtils.success(analysisService.getTask(taskId, currentUser.getId()));
    }

    @GetMapping("/result/{taskId}")
    public BaseResponse<AnalysisResultResponse> getResult(@PathVariable String taskId, HttpServletRequest request) {
        User currentUser = requireLogin(request);
        return ResultUtils.success(analysisService.getResult(taskId, currentUser.getId()));
    }

    private User requireLogin(HttpServletRequest request) {
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        if (!(userObj instanceof User)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        return (User) userObj;
    }
}
