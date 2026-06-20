package io.langya.module.ui;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import androidx.collection.LruCache;
import timber.log.Timber;

import io.langya.module.R;

/**
 * 异步加载通讯录头像到 ImageView 自带 LruCache
 * 单线程 executor 避免大量 IO 抢盘 list 滚动更顺
 *
 * 用法 通常和字母 fallback 配合: 调用前先把字母层 VISIBLE 头像层 GONE
 * 加载成功的回调里再切换可见性
 */
public final class ContactAvatars {

    private static final int CACHE_BYTES = 4 * 1024 * 1024; // 4MB 足够上百张 thumbnail
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "ContactAvatars-IO");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 标记 ImageView 当前正在加载哪个 URI 列表回收时 tag 变了就丢弃旧结果 */
    private static final int TAG_KEY = R.id.ivAvatar;

    private ContactAvatars() {}

    public interface Callback {
        /** 主线程回调 success=true 表示已 setImageBitmap success=false 时调用方做 fallback */
        void onLoaded(boolean success);
    }

    public static void load(Context ctx, String uri, ImageView target, Callback cb) {
        if (uri == null || uri.isEmpty() || target == null) {
            if (cb != null) cb.onLoaded(false);
            return;
        }
        target.setTag(TAG_KEY, uri);

        var cached = CACHE.get(uri);
        if (cached != null) {
            target.setImageBitmap(cached);
            if (cb != null) cb.onLoaded(true);
            return;
        }

        var appCtx = ctx.getApplicationContext();
        var ref = new WeakReference<>(target);
        IO.execute(() -> {
            Bitmap bmp = null;
            try (var in = appCtx.getContentResolver().openInputStream(Uri.parse(uri))) {
                if (in != null) bmp = BitmapFactory.decodeStream(in);
            } catch (Throwable t) {
                Timber.w(t, "decode avatar failed: %s", uri);
            }
            final Bitmap result = bmp;
            if (result != null) CACHE.put(uri, result);
            MAIN.post(() -> {
                var iv = ref.get();
                if (iv == null) return;
                // 视图复用后 tag 已经被换 这次结果就不属于它 丢弃
                Object tag = iv.getTag(TAG_KEY);
                if (!uri.equals(tag)) return;
                if (result != null) iv.setImageBitmap(result);
                if (cb != null) cb.onLoaded(result != null);
            });
        });
    }

    /** 在 onBindViewHolder 开头调用 防止视图复用时旧 bitmap 闪一下 */
    public static void clear(ImageView target) {
        if (target == null) return;
        target.setTag(TAG_KEY, null);
        target.setImageDrawable(null);
    }
}
