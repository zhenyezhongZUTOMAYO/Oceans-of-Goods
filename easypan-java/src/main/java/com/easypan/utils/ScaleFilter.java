package com.easypan.utils;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.*;
import java.io.File;
import java.math.BigDecimal;

public class ScaleFilter {
    private static final Logger logger = LoggerFactory.getLogger(ScaleFilter.class);


    public static Boolean createThumbnailWidthFFmpeg(File file, int thumbnailWidth, File targetFile, Boolean delSource) {
        try {
            BufferedImage src = ImageIO.read(file);
            //thumbnailWidth 缩略图的宽度   thumbnailHeight 缩略图的高度
            int sorceW = src.getWidth();
            int sorceH = src.getHeight();
            //小于 指定高宽不压缩
            if (sorceW <= thumbnailWidth) {
                return false;
            }
            compressImage(file, thumbnailWidth, targetFile, delSource);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public static void compressImageWidthPercentage(File sourceFile, BigDecimal widthPercentage, File targetFile) {
        try {
            BigDecimal widthResult = widthPercentage.multiply(new BigDecimal(ImageIO.read(sourceFile).getWidth()));
            compressImage(sourceFile, widthResult.intValue(), targetFile, true);
        } catch (Exception e) {
            logger.error("压缩图片失败");
        }
    }

    public static void createCover4Video(File sourceFile, Integer width, File targetFile) {
        try {
            String cmd = "ffmpeg -i %s -y -vframes 1 -vf scale=%d:%d/a %s";
            ProcessUtils.executeCommand(String.format(cmd, sourceFile.getAbsoluteFile(), width, width, targetFile.getAbsoluteFile()), false);
        } catch (Exception e) {
            logger.error("生成视频封面失败", e);
        }
    }

    public static void compressImage(File sourceFile, Integer width, File targetFile, Boolean delSource) {
        try {
            BufferedImage src = ImageIO.read(sourceFile);
            if (src == null) {
                throw new IllegalArgumentException("读取图片失败");
            }
            int sourceW = src.getWidth();
            int sourceH = src.getHeight();
            if (sourceW <= 0 || sourceH <= 0) {
                throw new IllegalArgumentException("非法图片尺寸");
            }
            int targetW = Math.min(width, sourceW);
            int targetH = (int) (((double) targetW / (double) sourceW) * sourceH);
            Image scaled = src.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
            BufferedImage output = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = output.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(scaled, 0, 0, null);
            g2d.dispose();

            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            String fileName = targetFile.getName();
            String format = "jpg";
            int dotIndex = fileName.lastIndexOf(".");
            if (dotIndex > -1 && dotIndex < fileName.length() - 1) {
                String ext = fileName.substring(dotIndex + 1).toLowerCase();
                if ("png".equals(ext) || "jpg".equals(ext) || "jpeg".equals(ext) || "bmp".equals(ext) || "gif".equals(ext) || "webp".equals(ext)) {
                    format = ext;
                }
            }
            ImageIO.write(output, format, targetFile);
            if (delSource) {
                FileUtils.forceDelete(sourceFile);
            }
        } catch (Exception e) {
            logger.error("压缩图片失败", e);
        }
    }

    public static void main(String[] args) {
        compressImageWidthPercentage(new File("C:\\Users\\Administrator\\Pictures\\微信图片_20230107141436.png"), new BigDecimal(0.7),
                new File("C:\\Users\\Administrator" +
                        "\\Pictures" +
                        "\\微信图片_202106281029182.jpg"));
    }
}