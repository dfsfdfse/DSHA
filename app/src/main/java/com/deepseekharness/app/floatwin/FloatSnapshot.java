package com.deepseekharness.app.floatwin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 离屏渲染悬浮窗页面为 PNG。
 *
 * <p>用途：UI 迭代。应用自己把 WebView 画到 Bitmap 再存文件，不需要 MediaProjection、
 * 不需要无障碍、不会弹任何系统确认，因此可以高频出图对照 dsh 的样式。
 */
public final class FloatSnapshot {
  private static final String BASE_URL = "https://float.dsha.local/preview/";
  private static final Handler main = new Handler(Looper.getMainLooper());

  private FloatSnapshot() {}

  /**
   * @param ctx 应用上下文
   * @param html 页面内容（为空时读取 path）
   * @param path 页面文件路径（html 为空时使用）
   * @param width 逻辑宽度（dp）
   * @param height 逻辑高度（dp）
   * @param out 输出 PNG 文件
   * @param done 回调（成功返回 true）
   */
  public static void render(
      Context ctx, String html, String path, int width, int height, File out, Callback done) {
    if (ctx == null || out == null) return;
    final Context app = ctx.getApplicationContext();
    final int w = Math.max(240, width);
    final int h = Math.max(240, height);
    String content = html == null ? "" : html;
    if (content.isEmpty() && path != null && !path.isEmpty()) {
      try {
        content =
            new String(
                java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                StandardCharsets.UTF_8);
      } catch (Throwable error) {
        content = "";
      }
    }
    final String page = content;
    main.post(
        () -> {
          final WebView web = new WebView(app);
          web.getSettings().setJavaScriptEnabled(true);
          web.getSettings().setDomStorageEnabled(true);
          web.setVerticalScrollBarEnabled(false);
          web.setHorizontalScrollBarEnabled(false);
          final float density = app.getResources().getDisplayMetrics().density;
          final int pxW = Math.round(w * density);
          final int pxH = Math.round(h * density);
          web.setLayoutParams(
              new android.view.ViewGroup.LayoutParams(pxW, pxH));
          web.measure(
              android.view.View.MeasureSpec.makeMeasureSpec(pxW, android.view.View.MeasureSpec.EXACTLY),
              android.view.View.MeasureSpec.makeMeasureSpec(pxH, android.view.View.MeasureSpec.EXACTLY));
          web.layout(0, 0, pxW, pxH);
          web.setWebViewClient(
              new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                  main.postDelayed(
                      () -> {
                        boolean ok = false;
                        try {
                          Bitmap bitmap = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888);
                          Canvas canvas = new Canvas(bitmap);
                          canvas.drawColor(0xFF11141A);
                          view.draw(canvas);
                          File parent = out.getParentFile();
                          if (parent != null) parent.mkdirs();
                          try (FileOutputStream stream = new FileOutputStream(out)) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                          }
                          bitmap.recycle();
                          ok = out.isFile() && out.length() > 0;
                        } catch (Throwable ignored) {
                        }
                        try {
                          view.destroy();
                        } catch (Throwable ignored) {
                        }
                        if (done != null) done.done(ok);
                      },
                      600);
                }
              });
          if (page.isEmpty()) {
            web.loadDataWithBaseURL(
                BASE_URL, "<html><body style='color:#fff'>empty</body></html>", "text/html", "utf-8", null);
          } else {
            web.loadDataWithBaseURL(BASE_URL, page, "text/html", "utf-8", null);
          }
        });
  }

  public interface Callback {
    void done(boolean ok);
  }
}
