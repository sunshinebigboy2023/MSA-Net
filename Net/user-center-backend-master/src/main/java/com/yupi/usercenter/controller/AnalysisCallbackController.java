package com.yupi.usercenter.controller;

import com.yupi.usercenter.common.BaseResponse;
import com.yupi.usercenter.common.ErrorCode;
import com.yupi.usercenter.common.ResultUtils;
import com.yupi.usercenter.config.MsaProperties;
import com.yupi.usercenter.exception.BusinessException;
import com.yupi.usercenter.model.domain.analysis.AnalysisTaskResponse;
import com.yupi.usercenter.model.domain.request.AnalysisCallbackRequest;
import com.yupi.usercenter.service.impl.AnalysisCacheService;
import com.yupi.usercenter.service.impl.AnalysisTaskService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/analysis/callback")
public class AnalysisCallbackController {

    private final AnalysisTaskService taskService;

    private final MsaProperties properties;

    public AnalysisCallbackController(AnalysisTaskService taskService, MsaProperties properties) {
        this.taskService = taskService;
        this.properties = properties;
    }

    @PostMapping
    public BaseResponse<AnalysisTaskResponse> complete(
            @RequestHeader(value = "X-MSA-Callback-Token", required = false) String token,
            @RequestBody AnalysisCallbackRequest request) {
        if (StringUtils.isBlank(properties.getCallbackToken())) {
            throw new BusinessException(ErrorCode.NO_AUTH, "MSA callback token is not configured");
        }
        if (!StringUtils.equals(properties.getCallbackToken(), token)) {
            throw new BusinessException(ErrorCode.NO_AUTH, "Invalid MSA callback token");
        }
        return ResultUtils.success(AnalysisCacheService.toTaskResponse(taskService.completeFromCallback(request)));
    }
}
