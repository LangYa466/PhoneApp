package io.langya.module.data;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import timber.log.Timber;

/**
 * libphonenumber 薄包装所有 String → 规范化数字串的逻辑都走这里 避免
 * 全 codebase 散落 {@code PhoneNumberUtils.normalizeNumber} + 末位截断的拼装
 *
 * 默认地区 = CN 因为本 app 的目标用户群体明确
 */
public final class PhoneNormalizer {

    private static final String DEFAULT_REGION = "CN";
    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

    private PhoneNormalizer() {}

    /**
     * 数字串归一化优先 E.164（不带 +）；解析失败回退到"仅保留数字字符"
     * 用作 contacts 索引 key 与缓存 key 保证同号不同写法落到同一 bucket
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        var parsed = parse(raw);
        if (parsed != null) {
            return parsed.getCountryCode() + String.valueOf(parsed.getNationalNumber());
        }
        return digitsOnly(raw);
    }

    /** 纯国内号（不含国家码） 用于跟系统给的来电号比较 */
    public static String national(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        var parsed = parse(raw);
        if (parsed != null) return String.valueOf(parsed.getNationalNumber());
        return digitsOnly(raw);
    }

    /** 是否合法的中国大陆手机号基于 libphonenumber 的类型判定 比"首位 1 + 11 位"更严格 */
    public static boolean isChinaMobile(String raw) {
        var parsed = parse(raw);
        if (parsed == null) return false;
        if (!UTIL.isValidNumberForRegion(parsed, DEFAULT_REGION)) return false;
        var type = UTIL.getNumberType(parsed);
        return type == PhoneNumberUtil.PhoneNumberType.MOBILE
                || type == PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE;
    }

    private static Phonenumber.PhoneNumber parse(String raw) {
        try {
            return UTIL.parse(raw, DEFAULT_REGION);
        } catch (NumberParseException e) {
            Timber.v("parse failed: %s (%s)", raw, e.getErrorType());
            return null;
        }
    }

    private static String digitsOnly(String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }
}
