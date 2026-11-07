package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebView;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.deepseekharness.app.util.WebPreviewPolicy;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/**
 * 受限的工作区动作桥：删除与安装。
 *
 * <p>与 {@link WebFileActions} 的 Blob 通道分开：本通道只接受容器内 {@code /root/**} 与
 * {@code /sdcard/**} 的绝对路径，不做任意本机路径访问；只对当前顶层同源页面开放。
 * 删除拒绝容器根与被保护目录，安装只接受 {@code .apk}。
 */
final class WorkspaceActions {
  private WorkspaceActions() {}

  private static final String CONTAINER = "linux/ubuntu";

  static void attach(WebView view, String base, BooleanSupplier current, Context ctx) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
    WebViewCompat.removeWebMessageListener(view, "DshaWorkspace");
    WebViewCompat.addWebMessageListener(
        view,
        "DshaWorkspace",
        Collections.singleton(base.substring(0, base.length() - 1)),
        (source, message, origin, mainFrame, reply) -> {
          if (!mainFrame
              || !current.getAsBoolean()
              || !WebPreviewPolicy.sameService(base, origin.toString())
              || !WebPreviewPolicy.sameService(base, source.getUrl())) return;
          String id = "";
          try {
            String text = message.getData();
            if (text == null || text.length() > 4096)
              throw new IllegalArgumentException("请求无效");
            JSONObject request = new JSONObject(text);
            id = request.getString("id");
            if (!id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("请求编号无效");
            String action = request.getString("action");
            String path = request.getString("path");
            File target = hostPath(ctx, path);
            if (action.equals("delete")) delete(target, path);
            else if (action.equals("install")) install(ctx, target, path);
            else throw new IllegalArgumentException("不支持的动作");
            reply.postMessage(
                new JSONObject().put("id", id).put("ok", true).put("detail", "").toString());
          } catch (Exception failure) {
            String detail = failure.getMessage();
            if (detail == null || detail.isEmpty()) detail = "操作失败";
            try {
              reply.postMessage(
                  new JSONObject()
                      .put("id", id)
                      .put("ok", false)
                      .put("detail", detail)
                      .toString());
            } catch (Exception ignored) {
            }
          }
        });
  }

  /** 容器内绝对路径 → 宿主文件；越界、包含 NUL 或父级回退一律拒绝。 */
  private static File hostPath(Context ctx, String path) throws IOException {
    if (path == null || path.isEmpty() || path.indexOf('\0') >= 0 || path.contains(".."))
      throw new IllegalArgumentException("路径无效");
    File base;
    String relative;
    if (path.equals("/sdcard") || path.startsWith("/sdcard/")) {
      base = android.os.Environment.getExternalStorageDirectory().getCanonicalFile();
      relative = path.equals("/sdcard") ? "" : path.substring("/sdcard/".length());
    } else if (path.startsWith("/root/")) {
      base = new File(ctx.getFilesDir(), CONTAINER).getCanonicalFile();
      relative = path.substring(1);
    } else {
      throw new IllegalArgumentException("只允许容器家目录或共享存储下的路径");
    }
    File target = new File(base, relative).getCanonicalFile();
    String basePath = base.getPath();
    if (!(target.getPath().equals(basePath)
        || target.getPath().startsWith(basePath + File.separator)))
      throw new IllegalArgumentException("路径越界");
    return target;
  }

  private static void delete(File target, String guest) throws IOException {
    if (guest.equals("/root") || guest.equals("/sdcard"))
      throw new IllegalArgumentException("不能删除根目录");
    if (guest.startsWith("/root/.dsh") || guest.equals("/root/.dsh"))
      throw new IllegalArgumentException("不能删除运行配置目录");
    deleteRecursive(target);
  }

  private static void deleteRecursive(File target) throws IOException {
    if (java.nio.file.Files.isSymbolicLink(target.toPath())) {
      // 只删链接本身，绝不跟随到链接目标。
      if (!java.nio.file.Files.deleteIfExists(target.toPath()))
        throw new IOException("删除失败");
      return;
    }
    if (target.isDirectory()) {
      File[] children = target.listFiles();
      if (children != null) for (File child : children) deleteRecursive(child);
    }
    if (!target.exists() || !target.delete()) throw new IOException("删除失败");
  }

  private static void install(Context ctx, File apk, String guest) throws IOException {
    if (!guest.toLowerCase(java.util.Locale.ROOT).endsWith(".apk"))
      throw new IllegalArgumentException("只能安装 .apk 文件");
    if (!apk.isFile() || apk.length() <= 0) throw new IllegalArgumentException("文件不存在或为空");
    File dir = new File(ctx.getCacheDir(), "updates");
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("缓存目录不可写");
    File staged = new File(dir, UUID.randomUUID() + "-" + apk.getName());
    try (FileInputStream in = new FileInputStream(apk);
        FileOutputStream out = new FileOutputStream(staged)) {
      byte[] buffer = new byte[1 << 16];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
    }
    Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".updates", staged);
    Intent intent =
        new Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    ctx.startActivity(intent);
  }
}
