package io.langya.module.callerid;

public interface CallerIdBatchListener {
    /** 识别完成 主线程触发name 为 null/空 = 未知号码 */
    void onResolved(String number, String name);
}
