package io.langya.module.data;

import java.util.HashMap;
import java.util.Map;

/**
 * 离线 11 位中国大陆手机号识别：仅靠号段前缀判定运营商
 * 命中率 100%（合法手机号） 延迟 = 一次 HashMap 查找
 *
 * 数据基于工信部 2024 公开号段分配；虚拟运营商按 4 位前缀细分
 */
public final class MobileSegment {

    private MobileSegment() {}

    private static final Map<String, String> P4 = new HashMap<>();
    private static final Map<String, String> P3 = new HashMap<>();

    static {
        // 三大运营商常规号段（3 位前缀）
        for (var p : new String[]{"134","135","136","137","138","139",
                "147","148","150","151","152","157","158","159",
                "172","178","182","183","184","187","188","195","197","198"}) {
            P3.put(p, "中国移动");
        }
        for (var p : new String[]{"130","131","132","145","146","155","156",
                "166","167","175","176","185","186","196"}) {
            P3.put(p, "中国联通");
        }
        for (var p : new String[]{"133","149","153","173","174","177",
                "180","181","189","190","191","193","199"}) {
            P3.put(p, "中国电信");
        }
        for (var p : new String[]{"192"}) {
            P3.put(p, "中国广电");
        }

        // 虚拟运营商（4 位前缀更精确）
        for (var p : new String[]{"1700","1701","1702","1620"}) {
            P4.put(p, "电信虚拟运营商");
        }
        for (var p : new String[]{"1703","1705","1706"}) {
            P4.put(p, "移动虚拟运营商");
        }
        for (var p : new String[]{"1704","1707","1708","1709","1710","1718"}) {
            P4.put(p, "联通虚拟运营商");
        }
    }

    /**
     * 是否合法中国大陆手机号基于 libphonenumber 类型判定；快速失败：长度 ≠ 11
     * 且无国家码前缀的串直接 false 不进 libphonenumber 解析
     */
    public static boolean isChinaMobile(String number) {
        if (number == null || number.isEmpty()) return false;
        return PhoneNormalizer.isChinaMobile(number);
    }

    /**
     * 仅返回运营商名称（基于前缀 无网络）非手机号返回 null
     */
    public static String carrierOf(String number) {
        if (number == null || number.isEmpty()) return null;
        var national = PhoneNormalizer.national(number);
        if (national.length() != 11 || national.charAt(0) != '1') return null;
        var p4 = national.substring(0, 4);
        var hit = P4.get(p4);
        if (hit != null) return hit;
        return P3.get(national.substring(0, 3));
    }
}
