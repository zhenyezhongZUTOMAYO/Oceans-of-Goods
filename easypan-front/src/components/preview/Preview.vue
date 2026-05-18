<template>
  <PreviewImage
    ref="imageViewerRef"
    :imageList="[imageUrl]"
    v-if="fileInfo.fileCategory == 3"
  ></PreviewImage>
  <Window
    :show="windowShow"
    @close="closeWindow"
    :width="fileInfo.fileCategory == 1 ? 1500 : isAiPreviewFile ? 1200 : 900"
    :title="fileInfo.fileName"
    :align="fileInfo.fileCategory == 1 ? 'center' : 'top'"
    v-else
  >
    <div class="preview-body">
      <div class="preview-main">
        <PreviewVideo :url="url" v-if="fileInfo.fileCategory == 1"></PreviewVideo>
        <PreviewExcel :url="url" v-if="fileInfo.fileType == 6"></PreviewExcel>
        <PreviewDoc :url="url" v-if="fileInfo.fileType == 5"></PreviewDoc>
        <PreviewPdf :url="url" v-if="fileInfo.fileType == 4"></PreviewPdf>
        <PreviewTxt
          :url="url"
          v-if="fileInfo.fileType == 7 || fileInfo.fileType == 8"
        ></PreviewTxt>
        <!--特殊预览-->
        <PreviewMusic
          :url="url"
          :fileName="fileInfo.fileName"
          v-if="fileInfo.fileCategory == 2"
        ></PreviewMusic>
        <PreviewDownload
          :createDownloadUrl="createDownloadUrl"
          :downloadUrl="downloadUrl"
          :fileInfo="fileInfo"
          v-if="fileInfo.fileCategory == 5 && fileInfo.fileType != 8"
        ></PreviewDownload>
      </div>
      <div class="ai-panel" v-if="isAiPreviewFile && showPartRef === 0">
        <div class="panel-title">
          <span>AI摘要</span>
          <el-button link type="primary" @click="refreshAiIndex">重新解析</el-button>
        </div>
        <div class="panel-content">
          <template v-if="aiSummaryLoading">加载中...</template>
          <template v-else>
            <div v-if="aiSummary.parseStatus === 0">AI正在解析中，请稍后刷新查看</div>
            <div v-else-if="aiSummary.parseStatus === 1">
              {{ aiSummary.parseError || "AI解析失败" }}
            </div>
            <template v-else>
              <div class="summary-text">{{ aiSummary.summary || "暂无摘要" }}</div>
              <div class="summary-tags" v-if="aiSummary.tags">
                标签：{{ aiSummary.tags }}
              </div>
            </template>
          </template>
        </div>
      </div>
    </div>
  </Window>
</template>

<script setup>
import PreviewDoc from "@/components/preview/PreviewDoc.vue";
import PreviewExcel from "@/components/preview/PreviewExcel.vue";
import PreviewImage from "@/components/preview/PreviewImage.vue";
import PreviewPdf from "@/components/preview/PreviewPdf.vue";
import PreviewVideo from "@/components/preview/PreviewVideo.vue";
import PreviewTxt from "@/components/preview/PreviewTxt.vue";
import PreviewDownload from "@/components/preview/PreviewDownload.vue";
import PreviewMusic from "@/components/preview/PreviewMusic.vue";

import { ref, reactive, getCurrentInstance, nextTick, computed } from "vue";
import { useRouter, useRoute } from "vue-router";
const { proxy } = getCurrentInstance();
const router = useRouter();
const route = useRoute();

const imageUrl = computed(() => {
  return (
    proxy.globalInfo.imageUrl + fileInfo.value.fileCover.replaceAll("_.", ".")
  );
});

const windowShow = ref(false);
const showPartRef = ref(0);
const closeWindow = () => {
  windowShow.value = false;
};

const api = {
  getAiSummary: "/file/getAiSummary",
  refreshAiIndex: "/file/refreshAiIndex",
};

const aiSummaryLoading = ref(false);
const aiSummary = ref({});
const isAiPreviewFile = computed(() => {
  return (
    fileInfo.value.fileType == 4 ||
    fileInfo.value.fileType == 5 ||
    fileInfo.value.fileType == 7 ||
    fileInfo.value.fileType == 8
  );
});
const FILE_URL_MAP = {
  0: {
    fileUrl: "/file/getFile",
    videoUrl: "/file/ts/getVideoInfo",
    createDownloadUrl: "/file/createDownloadUrl",
    downloadUrl: "/api/file/download",
  },
  1: {
    fileUrl: "/admin/getFile",
    videoUrl: "/admin/ts/getVideoInfo",
    createDownloadUrl: "/admin/createDownloadUrl",
    downloadUrl: "/api/admin/download",
  },
  2: {
    fileUrl: "/showShare/getFile",
    videoUrl: "/showShare/ts/getVideoInfo",
    createDownloadUrl: "/showShare/createDownloadUrl",
    downloadUrl: "/api/showShare/download",
  },
};
const url = ref(null);
const createDownloadUrl = ref(null);
const downloadUrl = ref(null);

const fileInfo = ref({});

const imageViewerRef = ref();
const showPreview = (data, showPart) => {
  fileInfo.value = data;
  showPartRef.value = showPart;
  if (data.fileCategory == 3) {
    nextTick(() => {
      imageViewerRef.value.show(0);
    });
  } else {
    windowShow.value = true;
    let _url = FILE_URL_MAP[showPart].fileUrl;
    //视频地址单独处理
    if (data.fileCategory == 1) {
      _url = FILE_URL_MAP[showPart].videoUrl;
    }
    let _createDownloadUrl = FILE_URL_MAP[showPart].createDownloadUrl;
    let _downloadUrl = FILE_URL_MAP[showPart].downloadUrl;
    if (showPart == 0) {
      _url = _url + "/" + data.fileId;
      _createDownloadUrl = _createDownloadUrl + "/" + data.fileId;
    } else if (showPart == 1) {
      _url = _url + "/" + data.userId + "/" + data.fileId;
      _createDownloadUrl =
        _createDownloadUrl + "/" + data.userId + "/" + data.fileId;
    } else if (showPart == 2) {
      _url = _url + "/" + data.shareId + "/" + data.fileId;
      _createDownloadUrl =
        _createDownloadUrl + "/" + data.shareId + "/" + data.fileId;
    }
    url.value = _url;
    createDownloadUrl.value = _createDownloadUrl;
    downloadUrl.value = _downloadUrl;
    if (showPart == 0 && isAiPreviewFile.value) {
      loadAiSummary();
    }
  }
};

const loadAiSummary = async () => {
  if (!fileInfo.value.fileId) {
    return;
  }
  aiSummaryLoading.value = true;
  let result = await proxy.Request({
    url: api.getAiSummary,
    params: {
      fileId: fileInfo.value.fileId,
    },
    showLoading: false,
  });
  aiSummaryLoading.value = false;
  if (!result) {
    return;
  }
  aiSummary.value = result.data || {};
};

const refreshAiIndex = async () => {
  let result = await proxy.Request({
    url: api.refreshAiIndex,
    params: {
      fileId: fileInfo.value.fileId,
    },
  });
  if (!result) {
    return;
  }
  proxy.Message.success("已提交重新解析");
  loadAiSummary();
};
defineExpose({ showPreview });
</script>

<style lang="scss" scoped>
.preview-body {
  display: flex;
  gap: 12px;
}

.preview-main {
  flex: 1;
  min-width: 0;
}

.ai-panel {
  width: 280px;
  border-left: 1px solid #ebeef5;
  padding-left: 12px;
}

.panel-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-weight: 600;
}

.panel-content {
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
}

.summary-text {
  white-space: pre-wrap;
}

.summary-tags {
  margin-top: 8px;
  color: #909399;
}
</style>