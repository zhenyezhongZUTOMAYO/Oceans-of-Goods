package com.easypan.entity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SysSettingsDto implements Serializable {
    /**
     * 注册发送邮件标题
     */
    private String registerEmailTitle = "邮箱验证码";

    /**
     * 注册发送邮件内容
     */
    private String registerEmailContent = "你好，您的邮箱验证码是：%s，15分钟有效";

    /**
     * 用户初始化空间大小 5M
     */
    private Integer userInitUseSpace = 5;

    /**
     * AI 模型名称
     */
    private String aiModel = "deepseek-v4-flash";

    /**
     * AI 服务Key
     */
    private String aiApiKey = "";

    /**
     * 是否启用AI能力 0:关闭 1:开启
     */
    private Integer aiEnable = 1;

    public String getRegisterEmailTitle() {
        return registerEmailTitle;
    }

    public void setRegisterEmailTitle(String registerEmailTitle) {
        this.registerEmailTitle = registerEmailTitle;
    }

    public String getRegisterEmailContent() {
        return registerEmailContent;
    }

    public void setRegisterEmailContent(String registerEmailContent) {
        this.registerEmailContent = registerEmailContent;
    }

    public Integer getUserInitUseSpace() {
        return userInitUseSpace;
    }

    public void setUserInitUseSpace(Integer userInitUseSpace) {
        this.userInitUseSpace = userInitUseSpace;
    }

    public String getAiModel() {
        return aiModel;
    }

    public void setAiModel(String aiModel) {
        this.aiModel = aiModel;
    }

    public String getAiApiKey() {
        return aiApiKey;
    }

    public void setAiApiKey(String aiApiKey) {
        this.aiApiKey = aiApiKey;
    }

    public Integer getAiEnable() {
        return aiEnable;
    }

    public void setAiEnable(Integer aiEnable) {
        this.aiEnable = aiEnable;
    }
}
