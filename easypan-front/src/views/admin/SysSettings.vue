<template>
  <div class="sys-setting-panel">
    <el-form
      :model="formData"
      :rules="rules"
      ref="formDataRef"
      label-width="150px"
      @submit.prevent
    >
      <!--input输入-->
      <el-form-item label="注册邮件标题" prop="registerEmailTitle">
        <el-input
          clearable
          placeholder="请输入注册邮件验证码邮件标题"
          v-model="formData.registerEmailTitle"
        ></el-input>
      </el-form-item>
      <!--textarea输入-->
      <el-form-item label="注册邮件标题" prop="registerEmailContent">
        <el-input
          clearable
          placeholder="请输入注册邮件验证码邮件内容%s占位符为验证码内容"
          v-model="formData.registerEmailContent"
        ></el-input>
      </el-form-item>
      <el-form-item label="初始空间大小" prop="userInitUseSpace">
        <el-input
          clearable
          placeholder="初始空间大小"
          v-model="formData.userInitUseSpace"
        >
          <template #suffix>MB</template>
        </el-input>
      </el-form-item>
      <el-form-item label="AI模型" prop="aiModel">
        <el-input
          clearable
          placeholder="例如: deepseek-v4-flash"
          v-model="formData.aiModel"
        ></el-input>
      </el-form-item>
      <el-form-item label="AI接口Key" prop="aiApiKey">
        <el-input
          clearable
          show-password
          placeholder="请输入AI接口Key"
          v-model="formData.aiApiKey"
        ></el-input>
      </el-form-item>
      <el-form-item label="启用AI" prop="aiEnable">
        <el-switch
          v-model="formData.aiEnable"
          :active-value="1"
          :inactive-value="0"
        />
      </el-form-item>
      <!-- 单选 -->
      <el-form-item label="" prop="">
        <el-button type="primary" @click="saveSettings">保存</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { ref, reactive, getCurrentInstance } from "vue";
import { useRouter, useRoute } from "vue-router";
const { proxy } = getCurrentInstance();
const router = useRouter();
const route = useRoute();

const api = {
  getSysSettings: "/admin/getSysSettings",
  saveSettings: "/admin/saveSysSettings",
};

const formData = ref({});
const formDataRef = ref();
const rules = {
  registerEmailTitle: [
    { required: true, message: "请输入注册邮件验证码邮件标题" },
  ],
  registerEmailContent: [
    { required: true, message: "请输入注册邮件验证码邮件内容" },
  ],
  userInitUseSpace: [
    { required: true, message: "请输入初始化空间大小" },
    {
      validator: proxy.Verify.number,
      message: "空间大小只能是数字",
    },
  ],
  aiModel: [{ required: true, message: "请输入AI模型名称" }],
};

const getSysSettings = async () => {
  let result = await proxy.Request({
    url: api.getSysSettings,
  });
  if (!result) {
    return;
  }
  formData.value = result.data;
  if (formData.value.aiEnable == undefined || formData.value.aiEnable == null) {
    formData.value.aiEnable = 1;
  }
};
getSysSettings();

const saveSettings = async () => {
  formDataRef.value.validate(async (valid) => {
    if (!valid) {
      return;
    }
    let params = Object.assign({}, formData.value);
    let result = await proxy.Request({
      url: api.saveSettings,
      params: params,
    });
    if (!result) {
      return;
    }
    proxy.Message.success("保存成功");
  });
};
</script>

<style lang="scss" scoped>
.sys-setting-panel {
  margin-top: 20px;
  width: 600px;
}
</style>