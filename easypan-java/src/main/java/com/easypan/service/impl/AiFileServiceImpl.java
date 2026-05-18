package com.easypan.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.easypan.component.RedisComponent;
import com.easypan.entity.config.AppConfig;
import com.easypan.entity.constants.Constants;
import com.easypan.entity.dto.SysSettingsDto;
import com.easypan.entity.enums.FileDelFlagEnums;
import com.easypan.entity.enums.FileTypeEnums;
import com.easypan.entity.po.AiFileChunk;
import com.easypan.entity.po.AiFileIndex;
import com.easypan.entity.po.FileInfo;
import com.easypan.entity.query.FileInfoQuery;
import com.easypan.entity.query.SimplePage;
import com.easypan.entity.vo.AiFileSummaryVO;
import com.easypan.entity.vo.AiSearchResultVO;
import com.easypan.entity.vo.PaginationResultVO;
import com.easypan.mappers.AiFileChunkMapper;
import com.easypan.mappers.AiFileIndexMapper;
import com.easypan.mappers.FileInfoMapper;
import com.easypan.service.AiFileService;
import com.easypan.utils.StringTools;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Base64;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service("aiFileService")
public class AiFileServiceImpl implements AiFileService {

    private static final Logger logger = LoggerFactory.getLogger(AiFileServiceImpl.class);

    private static final Integer PARSE_STATUS_PROCESSING = 0;
    private static final Integer PARSE_STATUS_FAIL = 1;
    private static final Integer PARSE_STATUS_SUCCESS = 2;
    private static final int SUMMARY_MAX_LENGTH = 220;
    private static final int CHUNK_MAX_LENGTH = 500;
    private static final int CHUNK_OVERLAP = 80;
    private static final int LLM_INPUT_MAX_LENGTH = 7000;
    private static final double SEARCH_MIN_SCORE = 0.15D;
    private static final String DEEPSEEK_CHAT_URL = "https://api.deepseek.com/chat/completions";

    private static final Set<String> STOP_WORDS = new HashSet<String>(Arrays.asList(
            "the", "and", "for", "with", "that", "this", "are", "was", "were", "to", "of", "in", "on", "a", "an",
            "是", "的", "了", "和", "与", "及", "在", "对", "并", "中", "或", "进行", "可以", "我们", "你们", "他们", "一个"
    ));

    @Resource
    private FileInfoMapper<FileInfo, FileInfoQuery> fileInfoMapper;
    @Resource
    private AiFileIndexMapper aiFileIndexMapper;
    @Resource
    private AiFileChunkMapper aiFileChunkMapper;
    @Resource
    private AppConfig appConfig;
    @Resource
    private RedisComponent redisComponent;

    @Override
    @Async
    public void asyncParseFile(String fileId, String userId) {
        try {
            parseFile(fileId, userId);
        } catch (Exception e) {
            logger.error("AI解析失败 fileId:{} userId:{}", fileId, userId, e);
            saveFailStatus(fileId, userId, e.getMessage());
        }
    }

    @Override
    public AiFileSummaryVO getAiSummary(String fileId, String userId) {
        if (isAiDisabled()) {
            AiFileSummaryVO vo = new AiFileSummaryVO();
            vo.setParseStatus(PARSE_STATUS_FAIL);
            vo.setParseError("AI服务已关闭，请联系管理员开启");
            return vo;
        }
        AiFileIndex aiFileIndex = aiFileIndexMapper.selectByFileIdAndUserId(fileId, userId);
        if (aiFileIndex == null) {
            asyncParseFile(fileId, userId);
            AiFileSummaryVO vo = new AiFileSummaryVO();
            vo.setParseStatus(PARSE_STATUS_PROCESSING);
            vo.setSummary("AI正在解析中，请稍后刷新查看摘要");
            return vo;
        }
        AiFileSummaryVO vo = new AiFileSummaryVO();
        vo.setParseStatus(aiFileIndex.getParseStatus());
        vo.setSummary(aiFileIndex.getSummary());
        vo.setTags(aiFileIndex.getTags());
        vo.setModelName(aiFileIndex.getModelName());
        vo.setParseError(aiFileIndex.getParseError());
        vo.setLastUpdateTime(aiFileIndex.getLastUpdateTime());
        return vo;
    }

    @Override
    public PaginationResultVO<AiSearchResultVO> search(String userId, String keyword, Integer pageNo, Integer pageSize) {
        if (isAiDisabled()) {
            return emptyPage(pageNo, pageSize);
        }
        String normalizedQuery = normalizeQuery(keyword);
        List<String> queryTokens = tokenize(normalizedQuery);
        if (queryTokens.isEmpty()) {
            return emptyPage(pageNo, pageSize);
        }
        List<AiFileChunk> chunkList = aiFileChunkMapper.selectByUserId(userId);
        if (chunkList == null || chunkList.isEmpty()) {
            return emptyPage(pageNo, pageSize);
        }

        Map<String, ScoredSnippet> bestByFile = new HashMap<String, ScoredSnippet>();
        Map<String, Integer> queryFreq = buildTokenFreqMap(queryTokens);
        for (AiFileChunk chunk : chunkList) {
            List<String> tokens = tokenize(chunk.getChunkText());
            if (tokens.isEmpty()) {
                continue;
            }
            double score = cosineSimilarity(queryFreq, buildTokenFreqMap(tokens));
            String chunkText = StringUtils.defaultString(chunk.getChunkText()).toLowerCase();
            if (!StringTools.isEmpty(normalizedQuery) && chunkText.contains(normalizedQuery)) {
                score += 0.8D;
            }
            if (score < SEARCH_MIN_SCORE) {
                continue;
            }
            String key = chunk.getFileId();
            ScoredSnippet old = bestByFile.get(key);
            if (old == null || score > old.score) {
                bestByFile.put(key, new ScoredSnippet(score, buildSnippet(chunk.getChunkText(), queryTokens, normalizedQuery)));
            }
        }
        if (bestByFile.isEmpty()) {
            return emptyPage(pageNo, pageSize);
        }

        FileInfoQuery fileInfoQuery = new FileInfoQuery();
        fileInfoQuery.setUserId(userId);
        fileInfoQuery.setDelFlag(FileDelFlagEnums.USING.getFlag());
        fileInfoQuery.setFileIdArray(bestByFile.keySet().toArray(new String[0]));
        List<FileInfo> fileInfoList = fileInfoMapper.selectList(fileInfoQuery);
        if (fileInfoList == null || fileInfoList.isEmpty()) {
            return emptyPage(pageNo, pageSize);
        }

        List<AiFileIndex> indexList = aiFileIndexMapper.selectByUserId(userId);
        Map<String, AiFileIndex> indexMap = new HashMap<String, AiFileIndex>();
        if (indexList != null && !indexList.isEmpty()) {
            indexMap = indexList.stream().collect(Collectors.toMap(AiFileIndex::getFileId, v -> v, (a, b) -> b));
        }
        List<AiSearchResultVO> all = new ArrayList<AiSearchResultVO>();
        for (FileInfo fileInfo : fileInfoList) {
            ScoredSnippet scored = bestByFile.get(fileInfo.getFileId());
            if (scored == null) {
                continue;
            }
            AiFileIndex index = indexMap.get(fileInfo.getFileId());
            double rerank = scored.score + 0.2D * tokenOverlapScore(queryTokens, tokenize(fileInfo.getFileName()));
            if (!StringTools.isEmpty(normalizedQuery) && StringUtils.defaultString(fileInfo.getFileName()).toLowerCase().contains(normalizedQuery)) {
                rerank += 0.5D;
            }
            if (index != null) {
                rerank += 0.1D * tokenOverlapScore(queryTokens, tokenize(index.getTags()));
                if (!StringTools.isEmpty(normalizedQuery) && StringUtils.defaultString(index.getSummary()).toLowerCase().contains(normalizedQuery)) {
                    rerank += 0.4D;
                }
            }
            AiSearchResultVO vo = new AiSearchResultVO();
            vo.setFileId(fileInfo.getFileId());
            vo.setFilePid(fileInfo.getFilePid());
            vo.setFileName(fileInfo.getFileName());
            vo.setFileCover(fileInfo.getFileCover());
            vo.setFileSize(fileInfo.getFileSize());
            vo.setFileType(fileInfo.getFileType());
            vo.setFileCategory(fileInfo.getFileCategory());
            vo.setFolderType(fileInfo.getFolderType());
            vo.setStatus(fileInfo.getStatus());
            vo.setLastUpdateTime(fileInfo.getLastUpdateTime());
            vo.setScore(roundScore(rerank));
            vo.setSnippet(scored.snippet);
            vo.setTags(index == null ? null : index.getTags());
            all.add(vo);
        }
        all.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return paginate(all, pageNo, pageSize);
    }

    @Transactional(rollbackFor = Exception.class)
    protected void parseFile(String fileId, String userId) throws Exception {
        if (isAiDisabled()) {
            return;
        }
        FileInfo fileInfo = fileInfoMapper.selectByFileIdAndUserId(fileId, userId);
        if (fileInfo == null || !FileDelFlagEnums.USING.getFlag().equals(fileInfo.getDelFlag())) {
            return;
        }
        if (!isSupportedDocType(fileInfo.getFileType())) {
            return;
        }
        Date now = new Date();
        SysSettingsDto settings = redisComponent.getSysSettingsDto();
        AiFileIndex processing = new AiFileIndex();
        processing.setFileId(fileId);
        processing.setUserId(userId);
        processing.setParseStatus(PARSE_STATUS_PROCESSING);
        processing.setModelName(settings.getAiModel());
        processing.setCreateTime(now);
        processing.setLastUpdateTime(now);
        aiFileIndexMapper.insertOrUpdate(processing);

        String fullPath = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + fileInfo.getFilePath();
        String content = normalizeText(extractText(new File(fullPath), fileInfo.getFileType()));
        if (StringTools.isEmpty(content)) {
            throw new RuntimeException("文档内容为空");
        }
        List<String> chunks = splitChunks(content);
        if (chunks.isEmpty()) {
            throw new RuntimeException("文档内容过短");
        }
        String summary = buildSummary(content);
        String tags = buildTags(content + " " + fileInfo.getFileName());
        LlmSummaryResult llmSummaryResult = generateByDeepSeek(content, fileInfo.getFileName(), settings);
        String parseError = null;
        String modelName = "local-fallback";
        if (llmSummaryResult != null) {
            if (!StringTools.isEmpty(llmSummaryResult.summary)) {
                summary = llmSummaryResult.summary;
            }
            if (!StringTools.isEmpty(llmSummaryResult.tags)) {
                tags = llmSummaryResult.tags;
            }
            modelName = StringTools.isEmpty(settings.getAiModel()) ? "deepseek-v4-flash" : settings.getAiModel();
        } else {
            if (StringTools.isEmpty(settings.getAiApiKey())) {
                parseError = "未配置AI Key，已使用本地摘要";
            } else {
                parseError = "DeepSeek调用失败，已使用本地摘要";
            }
        }

        aiFileChunkMapper.deleteByFileIdAndUserId(fileId, userId);
        List<AiFileChunk> list = new ArrayList<AiFileChunk>();
        for (int i = 0; i < chunks.size(); i++) {
            AiFileChunk chunk = new AiFileChunk();
            chunk.setFileId(fileId);
            chunk.setUserId(userId);
            chunk.setChunkIndex(i);
            chunk.setChunkText(chunks.get(i));
            chunk.setKeywords(buildTags(chunks.get(i)));
            list.add(chunk);
        }
        aiFileChunkMapper.insertBatch(list);

        AiFileIndex success = new AiFileIndex();
        success.setFileId(fileId);
        success.setUserId(userId);
        success.setParseStatus(PARSE_STATUS_SUCCESS);
        success.setSummary(summary);
        success.setTags(tags);
        success.setModelName(modelName);
        success.setParseError(parseError);
        success.setChunkCount(list.size());
        success.setCreateTime(now);
        success.setLastUpdateTime(new Date());
        aiFileIndexMapper.insertOrUpdate(success);
    }

    private LlmSummaryResult generateByDeepSeek(String content, String fileName, SysSettingsDto settings) {
        if (settings == null || StringTools.isEmpty(settings.getAiApiKey())) {
            return null;
        }
        String model = StringTools.isEmpty(settings.getAiModel()) ? "deepseek-v4-flash" : settings.getAiModel();
        try {
            JSONObject payload = new JSONObject();
            payload.put("model", model);
            payload.put("temperature", 0.2);
            JSONArray messages = new JSONArray();
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", "你是文档解析助手，只输出JSON。");
            messages.add(system);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", "输出 {\"summary\":\"...\",\"tags\":[\"标签1\",\"标签2\"]}。文件名:" + fileName + "，内容:" + StringUtils.substring(content, 0, LLM_INPUT_MAX_LENGTH));
            messages.add(user);
            payload.put("messages", messages);

            OkHttpClient client = new OkHttpClient.Builder().build();
            RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), payload.toJSONString());
            Request request = new Request.Builder()
                    .url(DEEPSEEK_CHAT_URL)
                    .addHeader("Authorization", "Bearer " + settings.getAiApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();
            Response response = null;
            try {
                response = client.newCall(request).execute();
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("DeepSeek响应异常, httpCode:{}", response == null ? -1 : response.code());
                    return null;
                }
                JSONObject root = JSONObject.parseObject(response.body().string());
                JSONArray choices = root.getJSONArray("choices");
                if (choices == null || choices.isEmpty()) {
                    logger.warn("DeepSeek返回choices为空");
                    return null;
                }
                String text = choices.getJSONObject(0).getJSONObject("message").getString("content");
                JSONObject json = extractJsonPayload(text);
                if (json == null) {
                    return null;
                }
                LlmSummaryResult result = new LlmSummaryResult();
                result.summary = StringUtils.substring(StringUtils.defaultString(json.getString("summary")), 0, SUMMARY_MAX_LENGTH);
                JSONArray tagsArr = json.getJSONArray("tags");
                if (tagsArr != null && !tagsArr.isEmpty()) {
                    List<String> tagsList = new ArrayList<String>();
                    for (int i = 0; i < tagsArr.size(); i++) {
                        String tag = tagsArr.getString(i);
                        if (!StringTools.isEmpty(tag)) {
                            tagsList.add(tag.trim());
                        }
                    }
                    result.tags = String.join(",", tagsList.stream().distinct().limit(8).collect(Collectors.toList()));
                } else {
                    result.tags = StringUtils.substring(StringUtils.defaultString(json.getString("tags")), 0, 200);
                }
                logger.info("DeepSeek调用成功, model:{}", model);
                return result;
            } finally {
                if (response != null && response.body() != null) {
                    response.body().close();
                }
            }
        } catch (Exception e) {
            logger.error("DeepSeek调用异常", e);
            return null;
        }
    }

    private JSONObject extractJsonPayload(String contentText) {
        if (StringTools.isEmpty(contentText)) {
            return null;
        }
        String cleaned = contentText.trim().replace("```json", "").replace("```", "");
        try {
            return JSONObject.parseObject(cleaned);
        } catch (Exception e) {
            int s = cleaned.indexOf("{");
            int t = cleaned.lastIndexOf("}");
            if (s >= 0 && t > s) {
                try {
                    return JSONObject.parseObject(cleaned.substring(s, t + 1));
                } catch (Exception ignore) {
                    return null;
                }
            }
            return null;
        }
    }

    private void saveFailStatus(String fileId, String userId, String errorMsg) {
        AiFileIndex fail = new AiFileIndex();
        fail.setFileId(fileId);
        fail.setUserId(userId);
        fail.setParseStatus(PARSE_STATUS_FAIL);
        fail.setParseError(StringUtils.substring(StringUtils.defaultString(errorMsg), 0, 500));
        fail.setLastUpdateTime(new Date());
        fail.setCreateTime(new Date());
        aiFileIndexMapper.insertOrUpdate(fail);
    }

    private boolean isAiDisabled() {
        SysSettingsDto settingsDto = redisComponent.getSysSettingsDto();
        return settingsDto.getAiEnable() == null || settingsDto.getAiEnable() == 0;
    }

    private boolean isSupportedDocType(Integer fileType) {
        return FileTypeEnums.PDF.getType().equals(fileType)
                || FileTypeEnums.WORD.getType().equals(fileType)
                || FileTypeEnums.TXT.getType().equals(fileType)
                || FileTypeEnums.PROGRAME.getType().equals(fileType)
                || FileTypeEnums.IMAGE.getType().equals(fileType);
    }

    private String extractText(File file, Integer fileType) throws Exception {
        if (!file.exists()) {
            return "";
        }
        if (FileTypeEnums.TXT.getType().equals(fileType) || FileTypeEnums.PROGRAME.getType().equals(fileType)) {
            return readTxt(file);
        }
        if (FileTypeEnums.PDF.getType().equals(fileType)) {
            return readPdf(file);
        }
        if (FileTypeEnums.WORD.getType().equals(fileType)) {
            return readDocx(file);
        }
        if (FileTypeEnums.IMAGE.getType().equals(fileType)) {
            return readImageByOcr(file);
        }
        return "";
    }

    private String readTxt(File file) throws IOException {
        try {
            return FileUtils.readFileToString(file, "UTF-8");
        } catch (Exception e) {
            return FileUtils.readFileToString(file, "GBK");
        }
    }

    private String readPdf(File file) throws IOException {
        try (PDDocument document = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private String readDocx(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(fis);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    private String readImageByOcr(File file) {
        String appId = appConfig.getXfyunOcrAppId();
        String apiKey = appConfig.getXfyunOcrApiKey();
        String apiSecret = appConfig.getXfyunOcrApiSecret();
        String ocrUrl = appConfig.getXfyunOcrUrl();
        if (StringTools.isEmpty(appId) || StringTools.isEmpty(apiKey) || StringTools.isEmpty(apiSecret) || StringTools.isEmpty(ocrUrl)) {
            logger.info("讯飞OCR未配置，跳过图片OCR");
            return "";
        }
        try {
            String host = "api.xf-yun.com";
            String path = "/v1/private/sf8e6aca1";
            String date = buildRfc1123Date();
            String requestLine = "POST " + path + " HTTP/1.1";
            String signatureOrigin = "host: " + host + "\n" + "date: " + date + "\n" + requestLine;
            String signature = hmacSha256Base64(signatureOrigin, apiSecret);
            String authorizationOrigin = "api_key=\"" + apiKey + "\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"" + signature + "\"";
            String authorization = Base64.getEncoder().encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8));
            String url = ocrUrl + "?authorization=" + URLEncoder.encode(authorization, "UTF-8")
                    + "&host=" + URLEncoder.encode(host, "UTF-8")
                    + "&date=" + URLEncoder.encode(date, "UTF-8");

            byte[] bytes = FileUtils.readFileToByteArray(file);
            String imageBase64 = Base64.getEncoder().encodeToString(bytes);
            String ext = StringUtils.substringAfterLast(file.getName().toLowerCase(), ".");
            if (StringTools.isEmpty(ext)) {
                ext = "jpg";
            }

            JSONObject requestBody = new JSONObject();
            JSONObject header = new JSONObject();
            header.put("app_id", appId);
            header.put("status", 3);
            requestBody.put("header", header);

            JSONObject parameter = new JSONObject();
            JSONObject svc = new JSONObject();
            svc.put("category", "ch_en_public_cloud");
            JSONObject result = new JSONObject();
            result.put("encoding", "utf8");
            result.put("compress", "raw");
            result.put("format", "json");
            svc.put("result", result);
            parameter.put("sf8e6aca1", svc);
            requestBody.put("parameter", parameter);

            JSONObject payload = new JSONObject();
            JSONObject imageData = new JSONObject();
            imageData.put("encoding", ext);
            imageData.put("status", 3);
            imageData.put("image", imageBase64);
            payload.put("sf8e6aca1_data_1", imageData);
            requestBody.put("payload", payload);

            OkHttpClient client = new OkHttpClient.Builder().build();
            RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), requestBody.toJSONString());
            Request request = new Request.Builder().url(url).post(body).build();
            Response response = null;
            try {
                response = client.newCall(request).execute();
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("讯飞OCR请求失败, code:{}", response == null ? -1 : response.code());
                    return "";
                }
                JSONObject root = JSONObject.parseObject(response.body().string());
                JSONObject rootHeader = root.getJSONObject("header");
                if (rootHeader == null || rootHeader.getIntValue("code") != 0) {
                    logger.warn("讯飞OCR返回异常:{}", rootHeader == null ? "null" : rootHeader.toJSONString());
                    return "";
                }
                String textBase64 = root.getJSONObject("payload").getJSONObject("result").getString("text");
                if (StringTools.isEmpty(textBase64)) {
                    return "";
                }
                String decodedText = new String(Base64.getDecoder().decode(textBase64), StandardCharsets.UTF_8);
                return extractOcrText(decodedText);
            } finally {
                if (response != null && response.body() != null) {
                    response.body().close();
                }
            }
        } catch (Exception e) {
            logger.error("讯飞OCR调用失败", e);
            return "";
        }
    }

    private String extractOcrText(String jsonText) {
        try {
            JSONObject jsonObject = JSONObject.parseObject(jsonText);
            JSONArray pages = jsonObject.getJSONArray("pages");
            if (pages == null || pages.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pages.size(); i++) {
                JSONObject page = pages.getJSONObject(i);
                JSONArray lines = page.getJSONArray("lines");
                if (lines == null) {
                    continue;
                }
                for (int j = 0; j < lines.size(); j++) {
                    JSONObject line = lines.getJSONObject(j);
                    JSONArray words = line.getJSONArray("words");
                    if (words == null) {
                        continue;
                    }
                    for (int k = 0; k < words.size(); k++) {
                        String content = words.getJSONObject(k).getString("content");
                        if (!StringTools.isEmpty(content)) {
                            sb.append(content).append(" ");
                        }
                    }
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            logger.error("解析OCR结果失败", e);
            return "";
        }
    }

    private String normalizeText(String text) {
        return StringUtils.normalizeSpace(StringUtils.defaultString(text).replace("\u0000", " "));
    }

    private String normalizeQuery(String keyword) {
        return StringUtils.defaultString(keyword).trim().toLowerCase();
    }

    private String buildRfc1123Date() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        return sdf.format(new Date());
    }

    private String hmacSha256Base64(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    private List<String> splitChunks(String content) {
        if (StringTools.isEmpty(content)) {
            return Collections.emptyList();
        }
        List<String> chunks = new ArrayList<String>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + CHUNK_MAX_LENGTH, content.length());
            String chunk = content.substring(start, end).trim();
            if (!StringTools.isEmpty(chunk)) {
                chunks.add(chunk);
            }
            if (end == content.length()) {
                break;
            }
            start = end - CHUNK_OVERLAP;
        }
        return chunks;
    }

    private String buildSummary(String content) {
        if (content.length() <= SUMMARY_MAX_LENGTH) {
            return content;
        }
        return content.substring(0, SUMMARY_MAX_LENGTH) + "...";
    }

    private String buildTags(String content) {
        Map<String, Integer> freq = buildTokenFreqMap(tokenize(content));
        LinkedHashMap<String, Integer> sorted = freq.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        return String.join(",", sorted.keySet());
    }

    private List<String> tokenize(String text) {
        if (StringTools.isEmpty(text)) {
            return Collections.emptyList();
        }
        String[] words = text.toLowerCase().split("[^\\p{L}\\p{N}\\u4e00-\\u9fa5]+");
        List<String> tokens = new ArrayList<String>();
        for (String word : words) {
            if (StringTools.isEmpty(word) || STOP_WORDS.contains(word)) {
                continue;
            }
            if (word.matches("[\\u4e00-\\u9fa5]+")) {
                if (word.length() >= 2) {
                    tokens.add(word);
                    for (int i = 0; i < word.length() - 1; i++) {
                        tokens.add(word.substring(i, i + 2));
                    }
                } else {
                    tokens.add(word);
                }
            } else {
                if (word.length() < 2) {
                    continue;
                }
                tokens.add(word);
            }
        }
        return tokens;
    }

    private Map<String, Integer> buildTokenFreqMap(List<String> tokens) {
        Map<String, Integer> map = new HashMap<String, Integer>();
        for (String token : tokens) {
            map.put(token, map.getOrDefault(token, 0) + 1);
        }
        return map;
    }

    private double cosineSimilarity(Map<String, Integer> map1, Map<String, Integer> map2) {
        if (map1.isEmpty() || map2.isEmpty()) {
            return 0D;
        }
        Set<String> terms = new HashSet<String>();
        terms.addAll(map1.keySet());
        terms.addAll(map2.keySet());
        double numerator = 0D;
        double sum1 = 0D;
        double sum2 = 0D;
        for (String term : terms) {
            double v1 = map1.getOrDefault(term, 0);
            double v2 = map2.getOrDefault(term, 0);
            numerator += v1 * v2;
            sum1 += v1 * v1;
            sum2 += v2 * v2;
        }
        if (sum1 == 0D || sum2 == 0D) {
            return 0D;
        }
        return numerator / (Math.sqrt(sum1) * Math.sqrt(sum2));
    }

    private double tokenOverlapScore(List<String> queryTokens, List<String> textTokens) {
        if (queryTokens.isEmpty() || textTokens.isEmpty()) {
            return 0D;
        }
        Set<String> querySet = new HashSet<String>(queryTokens);
        Set<String> textSet = new HashSet<String>(textTokens);
        int hit = 0;
        for (String term : querySet) {
            if (textSet.contains(term)) {
                hit++;
            }
        }
        return (double) hit / (double) querySet.size();
    }

    private String buildSnippet(String chunkText, List<String> queryTokens, String queryText) {
        if (StringTools.isEmpty(chunkText)) {
            return "";
        }
        String lower = chunkText.toLowerCase();
        int start = -1;
        if (!StringTools.isEmpty(queryText)) {
            int idx = lower.indexOf(queryText.toLowerCase());
            if (idx >= 0) {
                start = Math.max(0, idx - 25);
            }
        }
        if (start < 0) {
            start = 0;
            for (String token : queryTokens) {
                int idx = lower.indexOf(token.toLowerCase());
                if (idx >= 0) {
                    start = Math.max(0, idx - 20);
                    break;
                }
            }
        }
        int end = Math.min(chunkText.length(), start + 120);
        String snippet = chunkText.substring(start, end).trim();
        if (start > 0) {
            snippet = "..." + snippet;
        }
        if (end < chunkText.length()) {
            snippet = snippet + "...";
        }
        return snippet;
    }

    private double roundScore(double score) {
        return Math.round(score * 10000D) / 10000D;
    }

    private PaginationResultVO<AiSearchResultVO> emptyPage(Integer pageNo, Integer pageSize) {
        int size = pageSize == null || pageSize < 1 ? 15 : pageSize;
        int no = pageNo == null || pageNo < 1 ? 1 : pageNo;
        return new PaginationResultVO<AiSearchResultVO>(0, size, no, 0, Collections.<AiSearchResultVO>emptyList());
    }

    private PaginationResultVO<AiSearchResultVO> paginate(List<AiSearchResultVO> list, Integer pageNo, Integer pageSize) {
        int total = list.size();
        int size = pageSize == null || pageSize < 1 ? 15 : pageSize;
        int no = pageNo == null || pageNo < 1 ? 1 : pageNo;
        SimplePage page = new SimplePage(no, total, size);
        int start = page.getStart();
        int end = Math.min(start + page.getPageSize(), total);
        List<AiSearchResultVO> pageList = start >= total ? Collections.<AiSearchResultVO>emptyList() : list.subList(start, end);
        return new PaginationResultVO<AiSearchResultVO>(total, page.getPageSize(), page.getPageNo(), page.getPageTotal(), pageList);
    }

    private static class ScoredSnippet {
        private final double score;
        private final String snippet;

        private ScoredSnippet(double score, String snippet) {
            this.score = score;
            this.snippet = snippet;
        }
    }

    private static class LlmSummaryResult {
        private String summary;
        private String tags;
    }
}
