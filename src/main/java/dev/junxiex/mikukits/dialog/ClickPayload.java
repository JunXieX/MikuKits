package dev.junxiex.mikukits.dialog;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Dialog 按钮负载编码。
 * <p>
 * 线格式为 {@code page|action|kitId}，其中 kitId 先经 Base64Url 编码，仅含 {@code [A-Za-z0-9-_]}。
 * kitId 取自配置文件名，可能含 {@code |} 或 {@code "}：前者会破坏分段，后者会截断 SNBT 引号解析，
 * 编码后两种字符都不可能出现，线上格式因此始终安全。
 * 以 SNBT 字符串 {d:"..."} 形式放入 BinaryTagHolder，解析时取引号内文本。
 */
public final class ClickPayload {

    private static final Base64.Encoder B64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();

    public static final String SELECT = "select";
    public static final String CLAIM = "claim";
    public static final String PREV = "prev";
    public static final String NEXT = "next";
    public static final String BACK = "back";

    private ClickPayload() {
    }

    public static String encode(String action, String kitId, int page) {
        String safeKitId = B64_ENCODER.encodeToString(kitId.getBytes(StandardCharsets.UTF_8));
        return page + "|" + action + "|" + safeKitId;
    }

    /** 从 BinaryTagHolder.string()（形如 {d:"0|claim|starter"}）解析出编码串。 */
    public static String decode(String snbt) {
        if (snbt == null) {
            return "";
        }
        int first = snbt.indexOf('"');
        int last = snbt.lastIndexOf('"');
        if (first >= 0 && last > first) {
            return snbt.substring(first + 1, last);
        }
        return "";
    }

    public record Parsed(String action, String kitId, int page) {
    }

    public static Parsed parse(String encoded) {
        String[] parts = encoded.split("\\|", 3);
        int page = 0;
        if (parts.length > 0) {
            try {
                page = Integer.parseInt(parts[0]);
            } catch (NumberFormatException ignored) {
            }
        }
        String action = parts.length > 1 ? parts[1] : "";
        String kitId = "";
        if (parts.length > 2) {
            try {
                kitId = new String(B64_DECODER.decode(parts[2]), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // 非法负载（含被篡改/旧格式）按空 kitId 处理，交由上层回主菜单
            }
        }
        return new Parsed(action, kitId, page);
    }
}
