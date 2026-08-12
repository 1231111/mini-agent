package com.miniagent.agent.web;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * MiMo 原生多模态音视频白名单与 MIME 推断。
 * 格式对齐官方：音频 MP3/WAV/FLAC/M4A/OGG；视频 MP4/MOV/AVI/WMV。
 */
public final class MultimodalMedia {

    public static final String KIND_AUDIO = "audio";
    public static final String KIND_VIDEO = "video";

    /** Base64 串上限约 50MB → 源文件约 35MB */
    public static final long DEFAULT_MAX_BYTES = 35L * 1024 * 1024;

    private static final Set<String> AUDIO_EXT = Set.of(
            "mp3", "wav", "flac", "m4a", "ogg");
    private static final Set<String> VIDEO_EXT = Set.of(
            "mp4", "mov", "avi", "wmv");

    private MultimodalMedia() {}

    public static String kindOf(String filename, String mimeType) {
        String mime = StringUtils.defaultString(mimeType).toLowerCase(Locale.ROOT);
        if (mime.startsWith("audio/")) {
            String ext = ext(filename);
            if (ext.isEmpty() || AUDIO_EXT.contains(ext)) return KIND_AUDIO;
            return null;
        }
        if (mime.startsWith("video/")) {
            String ext = ext(filename);
            if (ext.isEmpty() || VIDEO_EXT.contains(ext)) return KIND_VIDEO;
            return null;
        }
        String e = ext(filename);
        if (AUDIO_EXT.contains(e)) return KIND_AUDIO;
        if (VIDEO_EXT.contains(e)) return KIND_VIDEO;
        return null;
    }

    public static boolean isNativeMedia(String filename, String mimeType) {
        return kindOf(filename, mimeType) != null;
    }

    public static String mimeOf(String filename, String mimeType, String kind) {
        String mime = StringUtils.trimToNull(mimeType);
        if (mime != null && !mime.equals("application/octet-stream")) return mime;
        String e = ext(filename);
        return switch (e) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "flac" -> "audio/flac";
            case "m4a" -> "audio/mp4";
            case "ogg" -> "audio/ogg";
            case "mp4" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "avi" -> "video/x-msvideo";
            case "wmv" -> "video/x-ms-wmv";
            default -> KIND_VIDEO.equals(kind) ? "video/mp4" : "audio/mpeg";
        };
    }

    public static boolean looksLikeMediaButUnsupported(String filename, String mimeType) {
        String mime = StringUtils.defaultString(mimeType).toLowerCase(Locale.ROOT);
        if (mime.startsWith("audio/") || mime.startsWith("video/"))
            return kindOf(filename, mimeType) == null;
        String e = ext(filename);
        return Set.of("webm", "mkv", "aac", "wma", "opus", "3gp", "mpeg", "mpg")
                .contains(e);
    }

    private static String ext(String filename) {
        if (StringUtils.isBlank(filename)) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
