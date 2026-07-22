package com.aimall.server.ai.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class KnowledgeTaskStatusRequest {
    @NotBlank @Size(max = 128) private String executionToken;
    @NotBlank @Pattern(regexp = "RUNNING|SUCCESS|FAILED|PARTIAL_FAILED") private String status;
    @Size(max = 100) private String currentStep;
    @Min(0) private Integer progressCurrent;
    @Min(0) private Integer progressTotal;
    @Size(max = 100) private String errorCode;
    @Size(max = 2000) private String errorMessage;
    private boolean errorCodePresent;
    private boolean errorMessagePresent;

    public String getExecutionToken() { return executionToken; }
    public void setExecutionToken(String executionToken) { this.executionToken = executionToken; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCurrentStep() { return currentStep; }
    public void setCurrentStep(String currentStep) { this.currentStep = currentStep; }
    public Integer getProgressCurrent() { return progressCurrent; }
    public void setProgressCurrent(Integer progressCurrent) { this.progressCurrent = progressCurrent; }
    public Integer getProgressTotal() { return progressTotal; }
    public void setProgressTotal(Integer progressTotal) { this.progressTotal = progressTotal; }
    public String getErrorCode() { return errorCode; }
    @JsonSetter("errorCode") public void setErrorCode(String errorCode) { this.errorCode = errorCode; this.errorCodePresent = true; }
    public String getErrorMessage() { return errorMessage; }
    @JsonSetter("errorMessage") public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; this.errorMessagePresent = true; }
    public boolean isErrorCodePresent() { return errorCodePresent; }
    public boolean isErrorMessagePresent() { return errorMessagePresent; }
}
