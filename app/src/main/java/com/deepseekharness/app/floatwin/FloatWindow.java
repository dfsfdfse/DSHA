package com.deepseekharness.app.floatwin;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.json.JSONObject;

/**
 * 富内容悬浮窗：原生窗口 + 本地 HTML 内容。
 *
 * <p>窗口尺寸固定后由页面自适应填满：整个窗口常驻，不会因为等待自动消失，只有用户点「关闭」才移除。
 * 拖动与缩放都由窗口内的原生触摸层完成（不是页面事件），手势不会因为窗口位移而中断：
 * 顶部一条触摸带负责移动，右下角与右/下边缘负责改变宽高，位置与尺寸在松手时持久化。
 * 所有拖动与缩放都限制在屏幕范围内，窗口不会被拖出屏幕或压在屏幕外。
 */
public final class FloatWindow {
  public interface Callback {
    void onConfirm(boolean allow);

    void onDismiss();
  }

  private static final String BASE_URL = "https://float.dsha.local/";
  private static final String BRIDGE = "DshaFloat";
  private static final String K_X = "floatwin_x";
  private static final String K_Y = "floatwin_y";
  private static final String K_W = "floatwin_w";
  private static final String K_H = "floatwin_h";

  private static final int HEAD_DP = 30;
  private static final int HANDLE_DP = 28;
  private static final int EDGE_DP = 12;
  /** 容器内与悬浮窗交换数据的两个文件（宿主直接读写 rootfs，不依赖 3090 桥）。 */
  private static final String GUEST_STATE = "float-state.json";

  private static final String GUEST_INBOX = "float-inbox.json";
  private static final int MIN_W_DP = 200;
  private static final int MIN_H_DP = 140;

  private static final Handler main = new Handler(Looper.getMainLooper());
  private static Context appCtx;
  private static FrameLayout container;
  private static WebView web;
  private static WindowManager wm;
  private static WindowManager.LayoutParams params;
  private static Callback callback;
  private static String pendingPayload = "";

  private static Context watchCtx;
  private static boolean watching;
  private static long stateStamp = -1L;
  /** 延后执行的「窗口切回不可获焦」，见 setFocusable 的注释。 */
  private static Runnable pendingBlur;

  /** true = 悬浮窗里跑的是 dsh 真实页面（输入栏/附件/语音全是原生的）。 */
  private static boolean realPage;
  /** 用户点过「关闭」：别在同一个 Web 会话里反复自动弹出。 */
  private static boolean userClosed;
  private static String lastWebUrl = "";
  private static android.webkit.ValueCallback<android.net.Uri[]> pendingFileChooser;
  private static boolean pendingImage;
  private static boolean pendingFile;
  /** 录音状态：true=正在录；用 dsh 的 speechToText 转写，不走系统识别。 */
  private static volatile boolean recording;
  /** 每次录音/取消递增；旧录音线程看到不一致就退出、不再回推。 */
  private static volatile long voiceRun;
  /** 正在经透明壳启动麦克风前台服务。 */
  private static boolean micStarting;
  /** 正在等转写结果的录音序号；0=没有在等。 */
  private static long voicePendingSeq;
  private static android.media.AudioRecord recorder;
  private static java.io.ByteArrayOutputStream pcm;
  private static long voiceSeq;
  private static long lastVoiceResult;

  private static final String GUEST_VOICE = "float-voice.json";
  private static final String GUEST_VOICE_RESULT = "float-voice-result.json";

  private static boolean moving;
  private static boolean resizing;
  private static boolean dragMoved;
  private static float downRawX;
  private static float downRawY;
  private static int baseX;
  private static int baseY;
  private static int baseW;
  private static int baseH;

  private FloatWindow() {}

  public static void show(Context context, String payload, Callback cb) {
    if (context == null) return;
    final Context app = context.getApplicationContext();
    callback = cb;
    pendingPayload = payload == null ? "" : payload;
    main.post(() -> showOnMain(app));
  }

  public static void update(String payload) {
    if (payload == null) return;
    pendingPayload = payload;
    main.post(
        () -> {
          if (web == null || realPage) return;
          web.evaluateJavascript(
              "window.dshFloat&&window.dshFloat.onData(" + JSONObject.quote(payload) + ")", null);
        });
  }

  /**
   * 开始轮询容器内的状态文件：dsh 插件把会话消息写进 /root/.dsh/float-state.json，
   * 宿主读它来显示/更新悬浮窗。走文件而不是 3090 桥，是因为与应用共存的另一个实例
   * 可能占着 3090，跨实例的 HTTP 推送会落到别人身上（表现为「悬浮窗不出现」）。
   */
  public static void startGuestWatch(Context context) {
    if (context == null) return;
    final Context app = context.getApplicationContext();
    main.post(
        () -> {
          watchCtx = app;
          if (watching) return;
          watching = true;
          main.postDelayed(FloatWindow::pollOnce, 600);
        });
  }

  private static void pollOnce() {
    try {
      pollReal();
    } catch (Throwable ignored) {
    }
    try {
      pollGuest();
    } catch (Throwable ignored) {
    }
    try {
      if (watchCtx != null) pollVoiceResult(watchCtx);
    } catch (Throwable ignored) {
    }
    // 流式输出时文件约 0.1 秒变一次：最近 4 秒有变化就 100ms 轮询（只 stat，不变不读），平时 700ms
    long idle = System.currentTimeMillis() - lastGuestChange;
    if (watching) main.postDelayed(FloatWindow::pollOnce, web != null && idle < 4000 ? 100 : 700);
  }

  /** dsh 一起好就把真实页面塞进悬浮窗 —— 输入栏、附件、模型、语音都是原生的。 */
  private static void pollReal() {
    // 悬浮窗只从入口打开：这里不再自动弹窗，也不再切到 dsh 真页面。
  }

  private static String webUrl(Context ctx) {
    try {
      com.deepseekharness.app.core.HarnessController hc =
          com.deepseekharness.app.core.HarnessController.get(ctx);
      if (hc == null) return null;
      String url = hc.getWebAuthUrl();
      if (url == null || url.length() < 8 || !url.startsWith("http")) return null;
      return url;
    } catch (Throwable error) {
      return null;
    }
  }

  /** 用户重新启动 dsh：允许这次启动完成后再次自动弹出（清掉「用户关过」的记忆）。 */
  public static void resetAutoShow() {
    main.post(
        () -> {
          userClosed = false;
          lastWebUrl = "";
        });
  }

  /** 打开自绘聊天窗（入口按钮用）：绝不加载 dsh 页面，先用容器里最新的状态渲染。 */
  /** 当前悬浮窗用哪套界面：dsh 原生 Web 界面（缩放后）或自绘界面。 */
  private static final String K_UI = "floatwin_ui";
  /** dsh 页面的字号缩放百分比（设置页里可调）。 */
  private static final String K_ZOOM = "floatwin_zoom";
  /** 设置页（原生视图，整窗覆盖）；打开时压在网页与窗口按钮之上。 */
  private static View settingsPanel;

  /**
   * 三档大小：1 迷你（默认：最新回答 + 一行输入，高度随内容）、2 标准（自绘的对话页）、
   * 3 完整（直接加载 dsh 页面）。1、2 是同一张自绘页切换样式，3 换成 dsh 页面。
   */
  private static final String K_TIER = "floatwin_tier";
  /** 完整档单独记高度：它比标准档高得多，共用一个值会互相覆盖。 */
  private static final String K_H3 = "floatwin_h3";
  private static final int MINI_DP = 112;
  private static int tier = 1;
  private static View btnShrink;
  private static View btnExpand;
  private static View btnMenu;
  private static View btnOpen;
  private static View btnClose;
  private static View dragStrip;
  private static View bottomEdge;
  private static View cornerHandle;

  private static int tierPref(Context app) {
    return Math.max(1, Math.min(3, prefs(app).getInt(K_TIER, 1)));
  }

  public static void showCustom(Context context) {
    showCustom(context, 0, null);
  }

  /** 切到 dsh 自己的 Web 界面：直接加载 dsh 页面并按窗口宽度缩放。 */
  private static void switchToDshUi(Context app) {
    prefs(app).edit().putString(K_UI, "dsh").apply();
    tier = 3;
    String url = webUrl(app);
    if (url == null) {
      evaluate("window.dshFloat&&window.dshFloat.onData({tasks:[]})");
      return;
    }
    if (web != null) dismiss();
    if (!create(app, false)) return;
    realPage = true;
    userClosed = false;
    applyTierChrome();
    try {
      web.loadUrl(url);
    } catch (Throwable ignored) {
    }
  }

  /** 切回自绘界面。 */
  private static void switchToCustomUi(Context app) {
    prefs(app).edit().putString(K_UI, "custom").apply();
    if (web != null) dismiss();
    showCustom(app, 0, null);
  }

  /**
   * 打开自绘聊天窗，并可选地设置透明度与隐藏区块。
   *
   * @param alpha 0=保持现有设置；20-100 设置透明度
   * @param hiddenBlocks 逗号分隔的区块名（tabs/usage/compose），null=保持现有显隐
   */
  public static void showCustom(Context context, int alpha, String hiddenBlocks) {
    if (context == null) return;
    final Context app = context.getApplicationContext();
    main.post(
        () -> {
          if (!com.deepseekharness.app.OverlayController.permitted(app)) return;
          ensureWake(app);
          // 档位决定页面：3 档直接是 dsh 页面；1、2 档是自绘页（dsh 没起来时 3 档退回 2 档）。
          int t = tierPref(app);
          if (t == 3 && webUrl(app) != null) {
            switchToDshUi(app);
            return;
          }
          tier = t == 3 ? 2 : t;
          if (web != null && realPage) dismiss();
          if (web == null && !create(app, true)) return;
          realPage = false;
          userClosed = false;
          applyTierChrome();
          evaluate("window.dshFloat&&window.dshFloat.setTier&&window.dshFloat.setTier(" + tier + ")");
          if (alpha > 0) {
            prefs(app)
                .edit()
                .putInt(K_OPACITY, Math.max(50, Math.min(100, alpha)))
                .putBoolean("floatwin_alpha_user", true)
                .apply();
          }
          if (hiddenBlocks != null) {
            prefs(app).edit().putString("floatwin_blocks", hiddenBlocks).apply();
            evaluate("window.dshFloat&&window.dshFloat.applyConfig(" + JSONObject.quote(hiddenBlocks) + ")");
          }
          pullState(app);
          if (web != null) pushPayload();
        });
  }

  /** 用 dsh 真实页面填充悬浮窗；窗口不存在就先建一个。 */
  public static void showRealPage(Context context) {
    if (context == null) return;
    final Context app = context.getApplicationContext();
    main.post(
        () -> {
          String url = webUrl(app);
          if (url == null) return;
          if (web != null && realPage) return;
          if (web != null) dismiss();
          tier = 3;
          prefs(app).edit().putInt(K_TIER, 3).apply();
          if (!create(app, false)) return;
          realPage = true;
          userClosed = false;
          try {
            web.loadUrl(url);
          } catch (Throwable ignored) {
          }
        });
  }

  private static long lastGuestChange;

  private static void pollGuest() {
    Context ctx = watchCtx;
    if (ctx == null) return;
    SharedPreferences p = prefs(ctx);
    if (!p.getBoolean("floatwin_enabled", true)) return;
    if (!com.deepseekharness.app.OverlayController.permitted(ctx)) return;
    File state = guestFile(ctx, GUEST_STATE);
    if (state == null || !state.isFile()) return;
    long stamp = state.lastModified() * 1_000_003L + state.length();
    if (stamp == stateStamp) return;
    lastGuestChange = System.currentTimeMillis();
    String json = readText(state);
    if (json == null || json.isEmpty()) return;
    if (json.contains("\"clear\"") && json.contains("true")) {
      stateStamp = stamp;
      dismiss();
      return;
    }
    stateStamp = stamp;
    if (web == null) return;  // 只有窗口已经打开时才更新；不再自动弹
    feed(ctx, json);
  }

  /**
   * 重新读一次容器里的状态文件，作为下一次推送的载荷。
   *
   * 打开窗口时页面还没加载完，那一帧推送会丢；所以页面加载完会主动发一次 refresh，
   * 这里必须真的去读盘，而不是沿用可能与现场已经脱节的旧载荷。
   */
  private static void pullState(Context ctx) {
    File state = guestFile(ctx, GUEST_STATE);
    String json = state != null && state.isFile() ? readText(state) : null;
    if (json != null && !json.isEmpty() && !json.contains("\"clear\"")) {
      pendingPayload = json;
      stateStamp = state.lastModified() * 1_000_003L + state.length();
    } else {
      pendingPayload = "{\"tasks\":[]}";
    }
  }

  /** 把插件写来的整份状态交给页面（窗口没开就开）。 */
  private static void feed(Context ctx, String json) {    if (realPage) return;
    if (web == null) {
      show(ctx, json, null);
      return;
    }
    pendingPayload = json;
    update(json);
  }

  /** 写入一条要发给 agent 的消息，插件轮询该文件并投递到会话。 */
  private static void writeInbox(
      Context ctx, String session, String text, String image, String file, boolean cancel) {
    try {
      File inbox = guestFile(ctx, GUEST_INBOX);
      if (inbox == null) return;
      JSONObject message =
          new JSONObject()
              .put("seq", System.currentTimeMillis())
              .put("session", session == null ? "" : session)
              .put("text", text == null ? "" : text)
              .put("image", image == null ? "" : image)
              .put("file", file == null ? "" : file)
              .put("cancel", cancel);
      File dir = inbox.getParentFile();
      if (dir != null) dir.mkdirs();
      File tmp = new File(inbox.getPath() + ".tmp");
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
        out.write(message.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.getFD().sync();
      }
      if (!tmp.renameTo(inbox)) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(inbox)) {
          out.write(message.toString().getBytes(StandardCharsets.UTF_8));
        }
      }
    } catch (Throwable ignored) {
    }
  }

  /** 让插件补一次数据（目前只有模型清单）：宿主不解释内容，只转达「请刷新」。 */
  private static void writeWant(Context ctx, String want) {
    writeWant(ctx, want, "");
  }

  private static void writeWant(Context ctx, String want, String session) {
    writeWant(ctx, want, session, "");
  }

  private static void writeWant(Context ctx, String want, String session, String value) {
    try {
      File inbox = guestFile(ctx, GUEST_INBOX);
      if (inbox == null || want == null || want.isEmpty()) return;
      File dir = inbox.getParentFile();
      if (dir != null) dir.mkdirs();
      JSONObject message =
          new JSONObject()
              .put("seq", System.currentTimeMillis())
              .put("want", want)
              .put("session", session == null ? "" : session)
              .put("value", value == null ? "" : value);
      File tmp = new File(inbox.getPath() + ".tmp");
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
        out.write(message.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.getFD().sync();
      }
      if (!tmp.renameTo(inbox)) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(inbox)) {
          out.write(message.toString().getBytes(StandardCharsets.UTF_8));
        }
      }
    } catch (Throwable ignored) {
    }
  }

  /** 切换会话模型：写一条带 provider/model 的收件箱消息。 */
  private static void writeModel(Context ctx, String session, String provider, String model) {
    try {
      File inbox = guestFile(ctx, GUEST_INBOX);
      if (inbox == null || model.isEmpty()) return;
      File dir = inbox.getParentFile();
      if (dir != null) dir.mkdirs();
      JSONObject message =
          new JSONObject()
              .put("seq", System.currentTimeMillis())
              .put("session", session == null ? "" : session)
              .put("text", "")
              .put("image", "")
              .put("cancel", false)
              .put("provider", provider == null ? "" : provider)
              .put("model", model);
      File tmp = new File(inbox.getPath() + ".tmp");
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
        out.write(message.toString().getBytes(StandardCharsets.UTF_8));
      }
      if (!tmp.renameTo(inbox)) tmp.renameTo(inbox);
    } catch (Throwable ignored) {
    }
  }

  /** rootfs 里的 /root/.dsh/<name>；环境没起来时返回 null。 */
  static File guestFile(Context ctx, String name) {
    try {
      com.deepseekharness.app.core.HarnessController hc =
          com.deepseekharness.app.core.HarnessController.get(ctx);
      if (hc == null || hc.getProot() == null) return null;
      File rootfs = hc.getProot().getRootfsDir();
      if (rootfs == null) return null;
      return new File(new File(new File(rootfs, "root"), ".dsh"), name);
    } catch (Throwable error) {
      return null;
    }
  }

  static String readText(File file) {
    try (java.io.FileInputStream in = new java.io.FileInputStream(file);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) {
        out.write(buffer, 0, n);
        if (out.size() > 262144) break;
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } catch (Throwable error) {
      return null;
    }
  }

  /** 窗口当前是否开着（入口打开的那扇）。 */
  public static boolean isOpen() {
    return web != null;
  }

  public static void hide(Context context) {
    main.post(FloatWindow::dismiss);
  }

  private static void showOnMain(Context app) {
    if (realPage) return;
    if (web == null && !create(app, true)) return;
    pushPayload();
  }

  private static boolean create(Context app, boolean loadCustomPage) {
    wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
    if (wm == null) return false;
    appCtx = app;

    WebView view = new WebView(app);
    view.setBackgroundColor(Color.TRANSPARENT);
    view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    view.getSettings().setJavaScriptEnabled(true);
    view.getSettings().setDomStorageEnabled(true);
    view.getSettings().setAllowFileAccess(false);
    view.getSettings().setAllowContentAccess(false);
    view.getSettings().setSupportZoom(false);
    // dsh 原生页面在 360dp 窗口里偏大：默认 75% 缩放；自绘页保持 100%。
    int zoom = loadCustomPage ? 100 : Math.max(50, Math.min(110, prefs(app).getInt(K_ZOOM, 90)));
    view.getSettings().setTextZoom(zoom);
    view.setVerticalScrollBarEnabled(false);
    view.setHorizontalScrollBarEnabled(false);
    view.setWebViewClient(
        new WebViewClient() {
          @Override
          public void onPageFinished(WebView v, String url) {
            if (realPage) {
              // dsh 页面自己带标题行，给窗口按钮腾出顶部一条，别压在「标准模式 / 文件夹」上
              try {
                v.evaluateJavascript(
                    "(function(){"
                        + "var r=document.getElementById('root')||document.body;"
                        + "if(!r||r.dataset.fwPad)return;"
                        + "r.dataset.fwPad='1';r.style.boxSizing='border-box';"
                        + "r.style.paddingTop='26px';"
                        + "})();",
                    null);
              } catch (Throwable ignored) {
              }
            }
          }
        });
    view.setWebChromeClient(
        new android.webkit.WebChromeClient() {
          @Override
          public void onPermissionRequest(final android.webkit.PermissionRequest request) {
            try {
              for (String resource : request.getResources()) {
                if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                  request.grant(new String[] {resource});
                  return;
                }
              }
            } catch (Throwable ignored) {
            }
            try {
              request.deny();
            } catch (Throwable ignored) {
            }
          }

          @Override
          public boolean onShowFileChooser(
              WebView view,
              android.webkit.ValueCallback<android.net.Uri[]> callback,
              FileChooserParams params) {
            pendingFileChooser = callback;
            try {
              android.content.Intent intent =
                  new android.content.Intent(app, FloatPickerActivity.class);
              intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
              app.startActivity(intent);
            } catch (Throwable error) {
              deliverPickedFile(null);
            }
            return true;
          }
        });

    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
      WebViewCompat.addWebMessageListener(
          view,
          BRIDGE,
          Collections.singleton(BASE_URL.substring(0, BASE_URL.length() - 1)),
          (source, message, origin, mainFrame, reply) -> {
            if (!mainFrame) return;
            onMessage(message.getData(), reply);
          });
    }

    FrameLayout frame = new FrameLayout(app);
    frame.addView(
        view,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    // 顶部拖动带：覆盖标题区，随窗口一起移动，手势稳定。
    View strip = transparent(app);
    FrameLayout.LayoutParams stripParams =
        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, HEAD_DP));
    stripParams.gravity = Gravity.TOP;
    stripParams.rightMargin = dp(app, 104);
    frame.addView(strip, stripParams);
    attachMove(strip, frame);
    dragStrip = strip;

    // 悬浮窗自己的按钮（从左到右）：缩小 / 放大 / 设置 / 回到应用 / 关闭。
    // 位置与显隐由 applyTierChrome 按档位排：迷你只留「放大」和「关闭」。
    btnShrink = iconButton(app, ICON_DOWN);
    btnShrink.setOnClickListener(v -> setTier(app, tier - 1));
    btnExpand = iconButton(app, ICON_UP);
    btnExpand.setOnClickListener(v -> setTier(app, tier + 1));
    btnMenu = iconButton(app, ICON_MENU);
    btnMenu.setOnClickListener(v -> toggleSettings(app));
    btnOpen = iconButton(app, ICON_OPEN);
    btnOpen.setOnClickListener(v -> openApp());
    btnClose = iconButton(app, ICON_CLOSE);
    btnClose.setOnClickListener(
        v -> {
          userClosed = true;
          dismiss();
        });
    for (View b : new View[] {btnShrink, btnExpand, btnMenu, btnOpen, btnClose}) {
      FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(app, 26), dp(app, 26));
      bp.gravity = Gravity.TOP | Gravity.END;
      bp.topMargin = dp(app, 2);
      frame.addView(b, bp);
    }

    // 边缘缩放：右边缘、下边缘与右下角，宽高由原生手势直接改窗口尺寸。
    View rightEdge = transparent(app);
    FrameLayout.LayoutParams rightParams =
        new FrameLayout.LayoutParams(dp(app, EDGE_DP), ViewGroup.LayoutParams.MATCH_PARENT);
    rightParams.gravity = Gravity.END | Gravity.TOP;
    rightParams.topMargin = dp(app, HEAD_DP);
    rightParams.bottomMargin = dp(app, EDGE_DP);
    frame.addView(rightEdge, rightParams);
    attachResize(rightEdge, frame, true, false);

    View bottom = transparent(app);
    FrameLayout.LayoutParams bottomParams =
        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, EDGE_DP));
    bottomParams.gravity = Gravity.BOTTOM | Gravity.START;
    bottomParams.rightMargin = dp(app, EDGE_DP);
    frame.addView(bottom, bottomParams);
    attachResize(bottom, frame, false, true);
    bottomEdge = bottom;

    View corner = transparent(app);
    FrameLayout.LayoutParams cornerParams = new FrameLayout.LayoutParams(dp(app, HANDLE_DP), dp(app, HANDLE_DP));
    cornerParams.gravity = Gravity.BOTTOM | Gravity.END;
    frame.addView(corner, cornerParams);
    attachResize(corner, frame, true, true);
    cornerHandle = corner;

    // 窗口保持不透明：半透明只由页面画。两边都设会叠乘（0.6×0.6=0.36），
    // 背后的网页文字透上来，悬浮窗里的字全糊在一起。
    frame.setAlpha(1f);

    WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
    lp.type =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
    lp.flags =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    // dsh 原生页面自己带输入框，窗口必须可获焦才能弹键盘；自绘页沿用「失焦即不可获焦」。
    if (loadCustomPage) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    lp.format = PixelFormat.TRANSLUCENT;
    int screenW = screenW(app);
    int screenH = screenH(app);
    SharedPreferences p = prefs(app);
    int defaultW = Math.min((int) (screenW * 0.94f), 760);
    lp.width = Math.max(dp(app, MIN_W_DP), Math.min(screenW, p.getInt(K_W, defaultW)));
    lp.height = tierHeight(app, loadCustomPage ? tier : 3);
    lp.gravity = Gravity.TOP | Gravity.START;
    lp.x = clamp(p.getInt(K_X, (screenW - lp.width) / 2), 0, Math.max(0, screenW - lp.width));
    lp.y = clamp(p.getInt(K_Y, dp(app, 64)), 0, Math.max(0, screenH - lp.height));

    try {
      wm.addView(frame, lp);
    } catch (Throwable error) {
      return false;
    }
    web = view;
    container = frame;
    params = lp;
    applyTierChrome();
    if (loadCustomPage) {
      view.loadDataWithBaseURL(
          BASE_URL, readAsset(app, "floatwin/index.html"), "text/html", "utf-8", null);
    }
    return true;
  }

  /**
   * 迷你 / 标准两档的底色不透明度（50–100，默认 80）。只作用于底色，文字始终不透明。
   * 用新键：旧键 floatwin_alpha 被早期版本强行抬成过 100，沿用会让默认一下子变成全不透明。
   */
  private static final String K_OPACITY = "floatwin_opacity";

  private static int alphaPercent(Context app) {
    try {
      return Math.max(50, Math.min(100, prefs(app).getInt(K_OPACITY, 80)));
    } catch (Throwable error) {
      return 80;
    }
  }

  private static android.widget.TextView overlayButton(Context app, String label) {
    android.widget.TextView view = new android.widget.TextView(app);
    boolean dark =
        (app.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    view.setText(label);
    view.setTextSize(15);
    // 和 dsh 的头像/图标按钮一致：无底色，只有 label-secondary 的前景色，按下才浮一层 hover。
    view.setTextColor(dark ? 0xB3CFD3D6 : 0xB361666B);
    view.setGravity(Gravity.CENTER);
    android.graphics.drawable.GradientDrawable normal =
        new android.graphics.drawable.GradientDrawable();
    normal.setColor(0x00000000);
    normal.setCornerRadius(dp(app, 999));
    android.graphics.drawable.GradientDrawable pressed =
        new android.graphics.drawable.GradientDrawable();
    pressed.setColor(dark ? 0x14FFFFFF : 0x0F000000);
    pressed.setCornerRadius(dp(app, 999));
    view.setBackground(
        new android.graphics.drawable.StateListDrawable() {
          {
            addState(new int[] {android.R.attr.state_pressed}, pressed);
            addState(new int[] {}, normal);
          }
        });
    return view;
  }

  // ───────────────────────────── 设置页 ─────────────────────────────
  // ≡ 打开：原生视图整窗覆盖当前网页（网页继续保持加载，收起即回来）。
  // 只用到 TextView / SeekBar，颜色与层级对齐 dsh 令牌，圆角只给 5–6dp。

  private static boolean isDark(Context app) {
    return (app.getResources().getConfiguration().uiMode
            & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
  }

  private static void toggleSettings(Context app) {
    if (settingsPanel != null) {
      closeSettings();
      return;
    }
    if (container == null) return;
    try {
      InputMethodManager imm =
          (InputMethodManager) app.getSystemService(Context.INPUT_METHOD_SERVICE);
      if (imm != null && web != null) imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
    } catch (Throwable ignored) {
    }
    try {
      View panel = buildSettings(app);
      container.addView(
          panel,
          new FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      settingsPanel = panel;
    } catch (Throwable ignored) {
    }
  }

  private static void closeSettings() {
    View panel = settingsPanel;
    settingsPanel = null;
    if (panel == null) return;
    try {
      if (container != null) container.removeView(panel);
    } catch (Throwable ignored) {
    }
  }

  private static View buildSettings(final Context app) {
    final boolean dark = isDark(app);
    final int bg = dark ? 0xFF16191F : 0xFFFFFFFF;
    final int fg = dark ? 0xFFF9FAFB : 0xFF0F1115;
    final int muted = dark ? 0xFFCFD3D6 : 0xFF61666B;
    final int tertiary = dark ? 0xFFADB2B8 : 0xFF81858C;
    final int line = dark ? 0x1AFFFFFF : 0x0F000000;
    final int field = dark ? 0xFF2C2C2E : 0xFFFFFFFF;
    final int selector = dark ? 0xFF353638 : 0xFFF5F6F7;
    final int hover = dark ? 0x14FFFFFF : 0x0F000000;
    final int accent = dark ? 0xFF7AAAFF : 0xFF4176E6;
    final int danger = 0xFFFF6B5E;
    final int hair = Math.max(1, Math.round(app.getResources().getDisplayMetrics().density * 0.5f));

    LinearLayout page = new LinearLayout(app);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setBackground(round(app, bg, 6, line, 1));

    // 标题行：设置 · 完成
    LinearLayout head = new LinearLayout(app);
    head.setOrientation(LinearLayout.HORIZONTAL);
    head.setGravity(Gravity.CENTER_VERTICAL);
    head.setPadding(dp(app, 14), 0, dp(app, 6), 0);
    head.addView(
        label(app, "设置", 14, fg, true),
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    android.widget.TextView done = label(app, "完成", 13, accent, false);
    done.setPadding(dp(app, 12), dp(app, 8), dp(app, 12), dp(app, 8));
    done.setBackground(pressable(app, hover, 5));
    done.setOnClickListener(v -> closeSettings());
    head.addView(
        done,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    page.addView(
        head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, 44)));
    page.addView(
        divider(app, line),
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hair));

    android.widget.ScrollView scroll = new android.widget.ScrollView(app);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setFillViewport(true);
    LinearLayout body = new LinearLayout(app);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(app, 14), dp(app, 2), dp(app, 14), dp(app, 12));
    scroll.addView(
        body,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

    // ── 大小：迷你 / 标准 / 完整（与窗口上的放大、缩小按钮是同一个档位）
    body.addView(section(app, "大小", tertiary));
    LinearLayout seg = new LinearLayout(app);
    seg.setOrientation(LinearLayout.HORIZONTAL);
    seg.setBackground(round(app, selector, 6, 0, 0));
    seg.setPadding(dp(app, 2), dp(app, 2), dp(app, 2), dp(app, 2));
    String[] cellNames = {"迷你", "标准", "完整"};
    for (int i = 0; i < 3; i++) {
      final int t = i + 1;
      boolean on = t == tier;
      android.widget.TextView cell = label(app, cellNames[i], 12, on ? fg : muted, false);
      cell.setGravity(Gravity.CENTER);
      cell.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
      cell.setBackground(on ? round(app, field, 5, 0, 0) : null);
      cell.setOnClickListener(v -> setTier(app, t));
      seg.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }
    body.addView(
        seg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, 32)));
    body.addView(hint(app, "迷你：最新回答 + 一行输入；标准：对话列表；完整：直接是 dsh。", tertiary));

    // ── 只对当前的界面类型显示相关项：dsh 页面调字号，自绘页调透明度与区块
    if (realPage) {
      body.addView(section(app, "字号", tertiary));
      final int zoom = Math.max(50, Math.min(110, prefs(app).getInt(K_ZOOM, 90)));
      LinearLayout zoomRow = row(app);
      zoomRow.addView(
          label(app, "缩放比例", 13, fg, false),
          new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      final android.widget.TextView zoomVal = label(app, zoom + "%", 12, muted, false);
      zoomRow.addView(
          zoomVal,
          new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      body.addView(
          zoomRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, 24)));
      android.widget.SeekBar zoomBar = new android.widget.SeekBar(app);
      zoomBar.setMax(60);
      zoomBar.setProgress(zoom - 50);
      tint(zoomBar, accent, selector);
      zoomBar.setOnSeekBarChangeListener(
          new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(
                android.widget.SeekBar bar, int progress, boolean fromUser) {
              int value = 50 + progress;
              zoomVal.setText(value + "%");
              if (!fromUser) return;
              prefs(app).edit().putInt(K_ZOOM, value).apply();
              if (web != null && realPage) web.getSettings().setTextZoom(value);
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {}
          });
      body.addView(
          zoomBar,
          new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, 34)));
      body.addView(hint(app, "只影响 dsh 页面；dsh 里的输入框、按钮跟着一起缩放。", tertiary));
    } else {
      body.addView(section(app, "不透明度", tertiary));
      final int alpha = alphaPercent(app);
      LinearLayout alphaRow = row(app);
      alphaRow.addView(
          label(app, "背景", 13, fg, false),
          new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      final android.widget.TextView alphaVal = label(app, alpha + "%", 12, muted, false);
      alphaRow.addView(
          alphaVal,
          new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      body.addView(
          alphaRow,
          new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, 24)));
      android.widget.SeekBar alphaBar = new android.widget.SeekBar(app);
      alphaBar.setMax(50);
      alphaBar.setProgress(alpha - 50);
      tint(alphaBar, accent, selector);
      alphaBar.setOnSeekBarChangeListener(
          new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(
                android.widget.SeekBar bar, int progress, boolean fromUser) {
              int value = 50 + progress;
              alphaVal.setText(value + "%");
              if (!fromUser) return;
              prefs(app)
                  .edit()
                  .putInt(K_OPACITY, value)
                  .putBoolean("floatwin_alpha_user", true)
                  .apply();
              evaluate(
                  "document.documentElement.style.setProperty('--alpha','"
                      + (value / 100.0)
                      + "')");
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {}
          });
      body.addView(
          alphaBar,
          new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, 34)));
      body.addView(hint(app, "只调底色，文字始终不透明；最低 50%。", tertiary));

      body.addView(section(app, "区块", tertiary));
      String[] blockKeys = {"tabs", "usage", "compose"};
      String[] blockNames = {"会话标签", "上下文用量", "输入栏"};
      for (int i = 0; i < blockKeys.length; i++) {
        body.addView(
            blockRow(app, blockKeys[i], blockNames[i], fg, muted, tertiary, accent, hover),
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, 36)));
      }
    }

    // ── 语音
    body.addView(section(app, "语音", tertiary));
    body.addView(
        switchRow(
            app,
            "录完直接发送",
            prefs(app).getBoolean(K_VOICE_SEND, false),
            fg,
            tertiary,
            accent,
            hover,
            on -> prefs(app).edit().putBoolean(K_VOICE_SEND, on).apply()),
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, 36)));
    body.addView(
        switchRow(
            app,
            "语音唤醒",
            FloatWake.enabled(app),
            fg,
            tertiary,
            accent,
            hover,
            on -> setWakeEnabled(app, on)),
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, 36)));
    body.addView(wakeWordsRow(app, fg, muted, tertiary, accent, field, line, hover));
    body.addView(
        hint(
            app,
            "语音唤醒：RCHA 在后台时说唤醒词即弹出悬浮窗并开始听；唤醒词后直接接指令也可以"
                + "（如「呼叫cc 看看今天的日程」）。用本机 SenseVoice 识别，不上传；常驻麦克风会多耗电。"
                + "多个唤醒词用逗号分隔。",
            tertiary));

    // ── 窗口操作
    body.addView(section(app, "窗口", tertiary));
    body.addView(
        actionRow(
            app,
            "刷新页面",
            fg,
            hover,
            () -> {
              if (web == null) {
                pullState(app);
                pushPayload();
                return;
              }
              closeSettings();
              try {
                web.reload();
              } catch (Throwable ignored) {
              }
            }));
    body.addView(
        actionRow(app, "复位位置与尺寸", fg, hover, () -> resetWindow(app)));
    body.addView(
        actionRow(
            app,
            "关闭悬浮窗",
            danger,
            hover,
            () -> {
              userClosed = true;
              dismiss();
            }));

    return page;
  }

  /** 设置页里的一行动作：整行可点，按下浮一层 hover。 */
  private static View actionRow(
      Context app, String text, int color, int hover, final Runnable run) {
    android.widget.TextView view = label(app, text, 13, color, false);
    view.setGravity(Gravity.CENTER_VERTICAL);
    view.setPadding(0, 0, 0, 0);
    view.setBackground(pressable(app, hover, 5));
    view.setOnClickListener(v -> run.run());
    view.setLayoutParams(
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, 40)));
    return view;
  }

  /** 自绘界面的区块开关：右侧显示开/关，点行切换（写 floatwin_blocks 再下发给页面）。 */
  private static View blockRow(
      Context app,
      final String key,
      String name,
      int fgColor,
      int mutedColor,
      int offColor,
      final int onColor,
      int hover) {
    final boolean[] on = {!hiddenBlocks(app).contains(key)};
    LinearLayout view = row(app);
    view.setPadding(0, 0, 0, 0);
    view.setBackground(pressable(app, hover, 5));
    view.addView(
        label(app, name, 13, fgColor, false),
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    final android.widget.TextView trail = label(app, on[0] ? "开" : "关", 12, on[0] ? onColor : offColor, false);
    view.addView(
        trail,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    view.setOnClickListener(
        v -> {
          on[0] = !on[0];
          trail.setText(on[0] ? "开" : "关");
          trail.setTextColor(on[0] ? onColor : offColor);
          java.util.List<String> names = hiddenBlocks(app);
          if (on[0]) names.remove(key);
          else if (!names.contains(key)) names.add(key);
          String joined = android.text.TextUtils.join(",", names);
          prefs(app).edit().putString("floatwin_blocks", joined).apply();
          evaluate("window.dshFloat&&window.dshFloat.applyConfig(" + JSONObject.quote(joined) + ")");
        });
    return view;
  }

  /** 开/关一行：右侧显示开/关，点整行切换。 */
  private static View switchRow(
      Context app,
      String name,
      boolean initial,
      int fgColor,
      int offColor,
      final int onColor,
      int hover,
      final java.util.function.Consumer<Boolean> change) {
    final boolean[] on = {initial};
    LinearLayout view = row(app);
    view.setBackground(pressable(app, hover, 5));
    view.addView(
        label(app, name, 13, fgColor, false),
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    final android.widget.TextView trail = label(app, on[0] ? "开" : "关", 12, on[0] ? onColor : offColor, false);
    view.addView(
        trail,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    view.setOnClickListener(
        v -> {
          on[0] = !on[0];
          trail.setText(on[0] ? "开" : "关");
          trail.setTextColor(on[0] ? onColor : offColor);
          change.accept(on[0]);
        });
    return view;
  }

  /** 唤醒词：显示当前值，点开就地编辑（窗口临时可获焦弹键盘）。 */
  private static View wakeWordsRow(
      final Context app, int fg, int muted, int tertiary, int accent, int field, int line, int hover) {
    final LinearLayout box = new LinearLayout(app);
    box.setOrientation(LinearLayout.VERTICAL);
    final LinearLayout show = row(app);
    show.setBackground(pressable(app, hover, 5));
    show.addView(
        label(app, "唤醒词", 13, fg, false),
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    show.addView(
        label(app, FloatWake.words(app), 12, muted, false),
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    box.addView(
        show, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, 36)));
    show.setOnClickListener(
        v -> {
          box.removeAllViews();
          LinearLayout edit = row(app);
          final android.widget.EditText input = new android.widget.EditText(app);
          input.setSingleLine(true);
          input.setText(FloatWake.words(app));
          input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
          input.setTextColor(fg);
          input.setHintTextColor(tertiary);
          input.setHint(FloatWake.DEFAULT_WORDS);
          input.setBackground(round(app, field, 4, line, 1));
          input.setPadding(dp(app, 8), 0, dp(app, 8), 0);
          input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
          edit.addView(input, new LinearLayout.LayoutParams(0, dp(app, 32), 1f));
          android.widget.TextView save = label(app, "保存", 13, accent, false);
          save.setGravity(Gravity.CENTER);
          save.setPadding(dp(app, 12), 0, dp(app, 4), 0);
          save.setBackground(pressable(app, hover, 5));
          edit.addView(
              save,
              new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(app, 32)));
          box.addView(
              edit,
              new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, 40)));
          final Runnable commit =
              () -> {
                String value = input.getText() == null ? "" : input.getText().toString().trim();
                prefs(app)
                    .edit()
                    .putString(FloatWake.K_WAKE_WORDS, value.isEmpty() ? FloatWake.DEFAULT_WORDS : value)
                    .apply();
                try {
                  InputMethodManager imm =
                      (InputMethodManager) app.getSystemService(Context.INPUT_METHOD_SERVICE);
                  if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
                } catch (Throwable ignored) {
                }
                applyFocusable(false);
                // 通知栏文字跟着更新
                if (FloatWake.isRunning()) {
                  try {
                    app.startService(
                        new android.content.Intent(app, FloatMicService.class)
                            .putExtra(FloatMicService.EXTRA_MODE, FloatMicService.MODE_WAKE));
                  } catch (Throwable ignored) {
                  }
                }
                refreshSettings(app);
              };
          save.setOnClickListener(x -> commit.run());
          input.setOnEditorActionListener(
              (tv, action, event) -> {
                commit.run();
                return true;
              });
          applyFocusable(true);
          input.requestFocus();
          input.setSelection(input.getText().length());
          main.postDelayed(
              () -> {
                try {
                  InputMethodManager imm =
                      (InputMethodManager) app.getSystemService(Context.INPUT_METHOD_SERVICE);
                  if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                } catch (Throwable ignored) {
                }
              },
              150);
        });
    return box;
  }

  private static java.util.List<String> hiddenBlocks(Context app) {
    java.util.List<String> names = new java.util.ArrayList<>();
    for (String part : prefs(app).getString("floatwin_blocks", "").split(",")) {
      String name = part.trim();
      if (!name.isEmpty()) names.add(name);
    }
    return names;
  }

  private static LinearLayout row(Context app) {
    LinearLayout view = new LinearLayout(app);
    view.setOrientation(LinearLayout.HORIZONTAL);
    view.setGravity(Gravity.CENTER_VERTICAL);
    return view;
  }

  private static android.widget.TextView section(Context app, String text, int color) {
    android.widget.TextView view = label(app, text, 11, color, false);
    view.setPadding(0, dp(app, 10), 0, dp(app, 6));
    return view;
  }

  private static android.widget.TextView hint(Context app, String text, int color) {
    android.widget.TextView view = label(app, text, 11, color, false);
    view.setPadding(0, dp(app, 6), 0, dp(app, 2));
    return view;
  }

  private static android.widget.TextView label(
      Context app, String text, float sizeSp, int color, boolean bold) {
    android.widget.TextView view = new android.widget.TextView(app);
    view.setText(text);
    view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp);
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    return view;
  }

  private static View divider(Context app, int color) {
    View view = new View(app);
    view.setBackgroundColor(color);
    return view;
  }

  private static android.graphics.drawable.GradientDrawable round(
      Context app, int color, int radiusDp, int stroke, int strokePx) {
    android.graphics.drawable.GradientDrawable shape =
        new android.graphics.drawable.GradientDrawable();
    shape.setColor(color);
    shape.setCornerRadius(dp(app, radiusDp));
    if (strokePx > 0) shape.setStroke(strokePx, stroke);
    return shape;
  }

  private static android.graphics.drawable.Drawable pressable(
      Context app, int hover, int radiusDp) {
    android.graphics.drawable.StateListDrawable states =
        new android.graphics.drawable.StateListDrawable();
    states.addState(
        new int[] {android.R.attr.state_pressed}, round(app, hover, radiusDp, 0, 0));
    states.addState(new int[] {}, round(app, 0x00000000, radiusDp, 0, 0));
    return states;
  }

  /** SeekBar 上色：只染进度与拇指，底槽用 selector 灰。 */
  private static void tint(android.widget.SeekBar bar, int color, int track) {
    try {
      bar.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
      bar.setThumbTintList(android.content.res.ColorStateList.valueOf(color));
      bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(track));
    } catch (Throwable ignored) {
    }
  }

  private static void toast(Context app, String text) {
    try {
      android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_SHORT).show();
    } catch (Throwable ignored) {
    }
  }

  /** 复位窗口：回到默认尺寸与居中位置。 */
  private static void resetWindow(Context app) {
    if (params == null || container == null) return;
    int screenW = screenW(app);
    int screenH = screenH(app);
    int width = Math.min((int) (screenW * 0.94f), 760);
    prefs(app).edit().remove(K_X).remove(K_Y).remove(K_W).remove(K_H).remove(K_H3).apply();
    params.width = Math.max(dp(app, MIN_W_DP), Math.min(screenW, width));
    params.height = tierHeight(app, tier);
    params.x = clamp((screenW - params.width) / 2, 0, Math.max(0, screenW - params.width));
    params.y = clamp(dp(app, 64), 0, Math.max(0, screenH - params.height));
    updateLayout(container);
  }

  private static void openApp() {
    Context ctx = web == null ? null : web.getContext();
    if (ctx != null) {
      try {
        android.content.Intent intent =
            ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (intent != null) {
          intent.addFlags(
              android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                  | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
          ctx.startActivity(intent);
        }
      } catch (Throwable ignored) {
      }
    }
    dismiss();
  }

  private static View transparent(Context app) {
    View v = new View(app);
    v.setBackgroundColor(Color.TRANSPARENT);
    return v;
  }

  /** 顶部触摸带：移动窗口，松手持久化；轻点切换最新一条消息。 */
  private static void attachMove(View strip, FrameLayout frame) {
    strip.setOnTouchListener(
        (v, event) -> {
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              moving = true;
              dragMoved = false;
              downRawX = event.getRawX();
              downRawY = event.getRawY();
              baseX = params.x;
              baseY = params.y;
              return true;
            case MotionEvent.ACTION_MOVE: {
              if (params == null) return true;
              int dx = Math.round(event.getRawX() - downRawX);
              int dy = Math.round(event.getRawY() - downRawY);
              if (!dragMoved && Math.abs(dx) + Math.abs(dy) > 6) dragMoved = true;
              if (dragMoved) {
                params.x = clamp(baseX + dx, 0, Math.max(0, screenW(appCtx) - params.width));
                params.y = clamp(baseY + dy, 0, Math.max(0, screenH(appCtx) - params.height));
                updateLayout(frame);
              }
              return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
              moving = false;
              if (dragMoved) persist();
              else if (web != null) {
                web.evaluateJavascript("window.dshFloat&&window.dshFloat.toggle()", null);
              }
              return true;
            }
            default:
              return true;
          }
        });
  }

  /** 缩放：horizontal 改宽，vertical 改高；始终保持窗口在屏幕内且不小于最小尺寸。 */
  private static void attachResize(View handle, FrameLayout frame, boolean horizontal, boolean vertical) {
    handle.setOnTouchListener(
        (v, event) -> {
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              resizing = true;
              downRawX = event.getRawX();
              downRawY = event.getRawY();
              baseW = params.width;
              baseH = params.height;
              baseX = params.x;
              baseY = params.y;
              return true;
            case MotionEvent.ACTION_MOVE: {
              if (params == null) return true;
              int dx = Math.round(event.getRawX() - downRawX);
              int dy = Math.round(event.getRawY() - downRawY);
              if (horizontal) {
                int maxW = Math.max(dp(appCtx, MIN_W_DP), screenW(appCtx) - baseX);
                params.width = clamp(baseW + dx, dp(appCtx, MIN_W_DP), maxW);
              }
              if (vertical) {
                int maxH = Math.max(dp(appCtx, MIN_H_DP), screenH(appCtx) - baseY);
                params.height = clamp(baseH + dy, dp(appCtx, MIN_H_DP), maxH);
              }
              params.x = clamp(params.x, 0, Math.max(0, screenW(appCtx) - params.width));
              params.y = clamp(params.y, 0, Math.max(0, screenH(appCtx) - params.height));
              updateLayout(frame);
              return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
              resizing = false;
              persist();
              return true;
            default:
              return true;
          }
        });
  }

  private static void updateLayout(View frame) {
    if (wm == null || params == null || frame == null) return;
    try {
      wm.updateViewLayout(frame, params);
    } catch (RuntimeException ignored) {
    }
  }

  private static void persist() {
    if (params == null || appCtx == null) return;
    prefs(appCtx)
        .edit()
        .putInt(K_X, params.x)
        .putInt(K_Y, params.y)
        .putInt(K_W, params.width)
        .apply();
    // 迷你档的高度跟着内容走，不记；标准档与完整档各记各的
    if (tier == 2) prefs(appCtx).edit().putInt(K_H, params.height).apply();
    else if (tier == 3) prefs(appCtx).edit().putInt(K_H3, params.height).apply();
  }

  /** 某一档打开时的窗口高度。 */
  private static int tierHeight(Context app, int t) {
    int screenH = screenH(app);
    SharedPreferences p = prefs(app);
    if (t == 1) return dp(app, MINI_DP);
    if (t == 3) {
      int def = Math.min(dp(app, 620), (int) (screenH * 0.72f));
      return Math.max(dp(app, MIN_H_DP), Math.min(screenH, p.getInt(K_H3, def)));
    }
    int def = Math.min(dp(app, 360), (int) (screenH * 0.5f));
    return Math.max(dp(app, MIN_H_DP), Math.min(screenH, p.getInt(K_H, def)));
  }

  /**
   * 切档。1↔2 只换自绘页的样式和窗口高度；进 3 档换成 dsh 页面，离开 3 档换回自绘页。
   * 窗口左上角位置不动，变高时碰到屏幕底就整体上移。
   */
  private static void setTier(Context app, int next) {
    int t = Math.max(1, Math.min(3, next));
    if (t == tier && web != null) return;
    if (t == 3 && webUrl(app) == null) {
      toast(app, "dsh 还没启动，先运行 dsh 才能展开成完整界面");
      return;
    }
    prefs(app).edit().putInt(K_TIER, t).apply();
    closeSettings();
    if (t == 3) {
      switchToDshUi(app);
      return;
    }
    if (realPage || web == null) {
      tier = t;
      if (web != null) dismiss();
      showCustom(app, 0, null);
      return;
    }
    tier = t;
    if (params != null && container != null) {
      int screenH = screenH(app);
      params.height = tierHeight(app, t);
      params.y = clamp(params.y, 0, Math.max(0, screenH - params.height));
      updateLayout(container);
    }
    applyTierChrome();
    evaluate("window.dshFloat&&window.dshFloat.setTier&&window.dshFloat.setTier(" + t + ")");
  }

  /** 按档位排窗口按钮：从右往左 关闭、回应用、设置、放大、缩小，看不见的不占位。 */
  private static void applyTierChrome() {
    Context app = appCtx;
    if (app == null || btnClose == null) return;
    boolean[] visible = {
      true, // close
      tier >= 2, // open app
      tier >= 2, // menu
      tier <= 2, // expand
      tier >= 2, // shrink
    };
    View[] order = {btnClose, btnOpen, btnMenu, btnExpand, btnShrink};
    int slot = 0;
    for (int i = 0; i < order.length; i++) {
      View b = order[i];
      b.setVisibility(visible[i] ? View.VISIBLE : View.GONE);
      if (!visible[i]) continue;
      FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) b.getLayoutParams();
      lp.rightMargin = dp(app, 6 + 28 * slot);
      b.setLayoutParams(lp);
      slot++;
    }
    if (dragStrip != null) {
      FrameLayout.LayoutParams sp = (FrameLayout.LayoutParams) dragStrip.getLayoutParams();
      sp.rightMargin = dp(app, 10 + 28 * slot);
      // 自绘页（1、2 档）顶行 28：左边是会话标签，拖动区从标签右边开始（页面报 dragzone）；
      // dsh 页面（3 档）整条都是拖动区
      sp.height = dp(app, tier == 3 ? HEAD_DP : 28);
      if (tier == 3) sp.leftMargin = 0;
      dragStrip.setLayoutParams(sp);
    }
    // 迷你档高度由内容决定，竖向缩放手柄收起，免得盖住输入栏
    int vis = tier == 1 ? View.GONE : View.VISIBLE;
    if (bottomEdge != null) bottomEdge.setVisibility(vis);
    if (cornerHandle != null) cornerHandle.setVisibility(vis);
  }

  private static final int ICON_CLOSE = 0;
  private static final int ICON_OPEN = 1;
  private static final int ICON_MENU = 2;
  private static final int ICON_UP = 3;
  private static final int ICON_DOWN = 4;

  /** 窗口按钮：dsh 那种无底色线性图标（1.3dp 描边、label-secondary），按下才浮一层圆形 hover。 */
  private static View iconButton(Context app, final int kind) {
    final boolean dark = isDark(app);
    final float density = app.getResources().getDisplayMetrics().density;
    final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    paint.setStyle(android.graphics.Paint.Style.STROKE);
    paint.setStrokeWidth(1.3f * density);
    paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
    paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
    paint.setColor(dark ? 0xFFCFD3D6 : 0xFF61666B);
    View v =
        new View(app) {
          private final android.graphics.Path path = new android.graphics.Path();

          @Override
          protected void onDraw(android.graphics.Canvas c) {
            super.onDraw(c);
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = 5.5f * density;
            path.reset();
            switch (kind) {
              case ICON_CLOSE:
                path.moveTo(cx - r * 0.75f, cy - r * 0.75f);
                path.lineTo(cx + r * 0.75f, cy + r * 0.75f);
                path.moveTo(cx + r * 0.75f, cy - r * 0.75f);
                path.lineTo(cx - r * 0.75f, cy + r * 0.75f);
                break;
              case ICON_OPEN:
                path.moveTo(cx - r * 0.7f, cy + r * 0.7f);
                path.lineTo(cx + r * 0.7f, cy - r * 0.7f);
                path.moveTo(cx - r * 0.15f, cy - r * 0.7f);
                path.lineTo(cx + r * 0.7f, cy - r * 0.7f);
                path.lineTo(cx + r * 0.7f, cy + r * 0.15f);
                break;
              case ICON_MENU:
                for (float dy : new float[] {-0.65f, 0f, 0.65f}) {
                  path.moveTo(cx - r * 0.85f, cy + r * dy);
                  path.lineTo(cx + r * 0.85f, cy + r * dy);
                }
                break;
              case ICON_UP:
                path.moveTo(cx - r * 0.8f, cy + r * 0.35f);
                path.lineTo(cx, cy - r * 0.45f);
                path.lineTo(cx + r * 0.8f, cy + r * 0.35f);
                break;
              default:
                path.moveTo(cx - r * 0.8f, cy - r * 0.35f);
                path.lineTo(cx, cy + r * 0.45f);
                path.lineTo(cx + r * 0.8f, cy - r * 0.35f);
                break;
            }
            c.drawPath(path, paint);
          }
        };
    v.setClickable(true);
    v.setContentDescription(
        kind == ICON_CLOSE ? "关闭" : kind == ICON_OPEN ? "回到应用" : kind == ICON_MENU ? "设置"
            : kind == ICON_UP ? "放大" : "缩小");
    android.graphics.drawable.GradientDrawable pressed = new android.graphics.drawable.GradientDrawable();
    pressed.setShape(android.graphics.drawable.GradientDrawable.OVAL);
    pressed.setColor(dark ? 0x14FFFFFF : 0x0F000000);
    android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
    bg.addState(new int[] {android.R.attr.state_pressed}, pressed);
    bg.addState(new int[] {}, new android.graphics.drawable.ColorDrawable(0));
    v.setBackground(bg);
    return v;
  }

  private static void onMessage(String data, JavaScriptReplyProxy reply) {
    try {
      JSONObject message = new JSONObject(data == null ? "{}" : data);
      String method = message.optString("method", "");
      JSONObject p = message.optJSONObject("params");
      if (p == null) p = new JSONObject();
      switch (method) {
        case "ready":
          pushPayload();
          if (pendingWake != null) main.postDelayed(FloatWindow::runPendingWake, 350);
          {
            Context ctx = web == null ? null : web.getContext();
            String blocks = ctx == null ? null : prefs(ctx).getString("floatwin_blocks", null);
            if (blocks != null) {
              evaluate("window.dshFloat&&window.dshFloat.applyConfig(" + JSONObject.quote(blocks) + ")");
            }
          }
          reply.postMessage("{\"ok\":true}");
          return;
        case "busy":
        case "expanded":
        case "activity":
          return;
        case "refresh": {
          // 页面刚加载 / 用户点了发送但手上没有会话：重读状态文件后再推一帧。
          Context ctx = web == null ? null : web.getContext();
          if (ctx != null) pullState(ctx);
          pushPayload();
          return;
        }
        case "files": {
          Context ctx = web == null ? null : web.getContext();
          if (ctx != null) {
            writeWant(ctx, "files", p.optString("session", ""), p.optString("query", ""));
          }
          return;
        }
        case "goal": {
          Context ctx = web == null ? null : web.getContext();
          if (ctx != null) {
            writeWant(ctx, "goal", p.optString("session", ""), p.optString("value", ""));
          }
          return;
        }
        case "permission": {
          Context ctx = web == null ? null : web.getContext();
          if (ctx != null) {
            writeWant(ctx, "permission", p.optString("session", ""), p.optString("value", ""));
          }
          return;
        }
        case "thread": {
          Context ctx = web == null ? null : web.getContext();
          if (ctx != null) writeWant(ctx, "thread", p.optString("session", ""));
          return;
        }
        case "ui": {
          Context ctx = web == null ? null : web.getContext();
          if (ctx != null && "dsh".equals(p.optString("value", ""))) setTier(ctx, 3);
          return;
        }
        case "tier": {
          Context ctx = web == null ? null : web.getContext();
          if (ctx != null) setTier(ctx, p.optInt("value", tier + 1));
          return;
        }
        case "dragzone": {
          if (realPage || dragStrip == null || appCtx == null) return;
          int left = Math.max(0, p.optInt("left", 0));
          FrameLayout.LayoutParams sp = (FrameLayout.LayoutParams) dragStrip.getLayoutParams();
          int px = dp(appCtx, left);
          if (sp.leftMargin == px) return;
          sp.leftMargin = px;
          dragStrip.setLayoutParams(sp);
          return;
        }
        case "fit": {
          // 迷你档：窗口高度贴着页面内容（最新回答 + 输入栏），回答展开时跟着长
          if (realPage || tier != 1 || params == null || container == null || appCtx == null) return;
          int h = p.optInt("h", 0);
          if (h <= 0) return;
          int screenH = screenH(appCtx);
          int px = clamp(dp(appCtx, h), dp(appCtx, 56), Math.min((int) (screenH * 0.6f), dp(appCtx, 440)));
          if (Math.abs(px - params.height) < 2) return;
          params.height = px;
          params.y = clamp(params.y, 0, Math.max(0, screenH - px));
          updateLayout(container);
          return;
        }
        case "threads": {
          Context ctx = web == null ? null : web.getContext();
          if (ctx != null) writeWant(ctx, "threads");
          return;
        }
        case "models": {
          // 模型清单还没拿到：让插件拉一次再回写状态，别让菜单点了没反应。
          Context ctx = web == null ? null : web.getContext();
          if (ctx != null) writeWant(ctx, "models");
          return;
        }
        case "resize":
          if (container != null && wm != null && params != null) {
            updateLayout(container);
          }
          return;
        case "alpha": {
          Context ctx = web == null ? null : web.getContext();
          int alpha = Math.max(50, Math.min(100, p.optInt("value", 80)));
          if (ctx != null) {
            prefs(ctx)
                .edit()
                .putInt(K_OPACITY, alpha)
                .putBoolean("floatwin_alpha_user", true)
                .apply();
          }
          // 页面已经按新值重画（--alpha），窗口本身不跟着变，避免叠乘
          return;
        }
        case "model": {
          Context ctx = web == null ? null : web.getContext();
          if (ctx != null)
            writeModel(
                ctx, p.optString("session", ""), p.optString("provider", ""), p.optString("model", ""));
          return;
        }
        case "cancel": {
          Context ctx = web == null ? null : web.getContext();
          if (ctx != null) writeInbox(ctx, p.optString("session", ""), "", "", "", true);
          return;
        }
        case "voice": {
          startVoice();
          return;
        }
        case "voiceStop": {
          if (recording) stopAndTranscribe();
          return;
        }
        case "voiceCancel": {
          cancelVoice();
          return;
        }
        case "pickImage": {
          pickImage();
          return;
        }
        case "pickFile": {
          pickFile();
          return;
        }
        case "send": {
          Context ctx = web == null ? null : web.getContext();
          if (ctx != null)
            writeInbox(
                ctx,
                p.optString("session", ""),
                p.optString("text", ""),
                p.optString("image", ""),
                p.optString("file", ""),
                false);
          return;
        }
        case "focus": {
          setFocusable(p.optBoolean("value", true));
          return;
        }
        case "confirm": {
          Callback cb = callback;
          if (cb != null) cb.onConfirm(p.optBoolean("allow", false));
          return;
        }
        case "open": {
          Context ctx = web == null ? null : web.getContext();
          if (ctx != null) {
            android.content.Intent intent =
                ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (intent != null) {
              intent.addFlags(
                  android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                      | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
              try {
                ctx.startActivity(intent);
              } catch (RuntimeException ignored) {
              }
            }
          }
          dismiss();
          return;
        }
        case "dismiss": {
          dismiss();
          Callback cb = callback;
          if (cb != null) cb.onDismiss();
          return;
        }
        default:
          reply.postMessage("{\"ok\":false,\"error\":\"unknown method\"}");
      }
    } catch (Exception ignored) {
    }
  }

  private static void pushPayload() {
    if (web == null) return;
    web.evaluateJavascript(
        "window.dshFloat&&window.dshFloat.onData(" + JSONObject.quote(withChrome()) + ")", null);
  }

  /** 宿主配置（半透明度）合进载荷，页面只负责显示。 */
  private static String withChrome() {
    try {
      JSONObject payload = new JSONObject(pendingPayload.isEmpty() ? "{}" : pendingPayload);
      Context ctx = web == null ? null : web.getContext();
      if (ctx != null) {
        int alpha = alphaPercent(ctx);
        payload.put("alpha", alpha);
        boolean dark =
            (ctx.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        payload.put("theme", dark ? "dark" : "light");
        payload.put("tier", tier);
        payload.put("wake", FloatWake.isRunning() ? FloatWake.words(ctx) : "");
      }
      return payload.toString();
    } catch (Exception ignored) {
      return pendingPayload;
    }
  }

  /**
   * 页面要输入时把窗口切成可获焦，否则键盘弹不出来；失焦后切回不抢焦点。
   *
   * <p>两个坑必须避开，否则「按钮点不动」：
   * <ol>
   *   <li>按在按钮上的那一下会先让输入框失焦。失焦就改窗口 flags 时，
   *       {@code updateViewLayout} 会重建输入通道，正在按着的手势被判为取消，
   *       手指抬起后的 click 永远不来 —— 打字正常、按钮全哑。</li>
   *   <li>flags 没变化时的重复 update 同样是白改一次窗口布局。</li>
   * </ol>
   * 所以：变成不可获焦延后到这一下点击走完；状态没变就什么都不做。
   */
  private static void setFocusable(boolean on) {
    if (on) {
      if (pendingBlur != null) {
        main.removeCallbacks(pendingBlur);
        pendingBlur = null;
      }
      applyFocusable(true);
      return;
    }
    if (pendingBlur != null) return;
    pendingBlur =
        () -> {
          pendingBlur = null;
          applyFocusable(false);
        };
    main.postDelayed(pendingBlur, 240);
  }

  private static void applyFocusable(boolean on) {
    if (params == null || wm == null || container == null) {
      if (on && web != null) web.requestFocus();
      return;
    }
    boolean focusable = (params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
    if (focusable != on) {
      if (on) params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
      else params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
      try {
        wm.updateViewLayout(container, params);
      } catch (RuntimeException ignored) {
      }
    }
    if (web == null) return;
    if (on) {
      web.requestFocus();
    } else {
      try {
        InputMethodManager imm =
            (InputMethodManager) web.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
      } catch (Throwable ignored) {
      }
    }
  }

  /** 语音：点一下开始录音，再点一下停止并把 WAV 交给容器里的 speechToText 转写。
   *  录音期间每 ~100ms 把音量/时长推给页面；说过话后静音 1.8 秒自动结束，最长 40 秒。 */
  private static void startVoice() {
    Context ctx = web == null ? null : web.getContext();
    if (ctx == null) return;
    if (recording) {
      stopAndTranscribe();
      return;
    }
    if (micStarting) return;
    if (FloatMicService.foreground) {
      // 语音唤醒常驻的 microphone 前台服务已在：直接录
      beginRecording();
      return;
    }
    // 悬浮窗时应用在后台，直接录只会拿到全零数据：先经透明壳拿前台身份，
    // 申请权限并启动 microphone 前台服务，服务就绪后才真正开录。
    micStarting = true;
    final long run = ++voiceRun;
    try {
      ctx.startActivity(
          new android.content.Intent(ctx, FloatMicActivity.class)
              .addFlags(
                  android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                      | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION
                      | android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
    } catch (Throwable error) {
      micStarting = false;
      evaluate("window.dshFloat&&window.dshFloat.onVoiceError('无法启动录音')");
      return;
    }
    main.postDelayed(
        () -> {
          if (!micStarting || voiceRun != run) return;
          micStarting = false;
          FloatMicActivity.close();
          evaluate("window.dshFloat&&window.dshFloat.onVoiceError('麦克风启动超时，请重试')");
        },
        8000);
  }

  /** 透明壳里的 microphone 前台服务已启动（或失败）。 */
  static void onMicService(boolean ok) {
    main.post(
        () -> {
          if (!micStarting) {
            // 已取消/超时：服务不需要了
            if (ok) stopMicService();
            FloatMicActivity.close();
            return;
          }
          micStarting = false;
          if (!ok) {
            FloatMicActivity.close();
            evaluate("window.dshFloat&&window.dshFloat.onVoiceError('系统不允许后台录音，请打开 RCHA 后再试')");
            return;
          }
          beginRecording();
          // 录音已在前台身份下开始，稍后关掉透明壳
          main.postDelayed(FloatMicActivity::close, 250);
        });
  }

  static void onMicDenied() {
    main.post(
        () -> {
          micStarting = false;
          evaluate("window.dshFloat&&window.dshFloat.onVoiceError('没有麦克风权限')");
        });
  }

  /** 一段录音结束：唤醒开着就把麦克风还给唤醒监听，否则停掉前台服务。 */
  private static void stopMicService() {
    Context ctx = anyCtx();
    FloatWake.resume();
    if (ctx == null) return;
    FloatMicService.recDone(ctx);
  }

  private static Context anyCtx() {
    if (web != null) return web.getContext().getApplicationContext();
    return watchCtx != null ? watchCtx : wakeCtx;
  }

  // ───────────────────────────── 语音唤醒 ─────────────────────────────
  private static Context wakeCtx;
  /** 唤醒后等页面就绪要做的事：null=没有；""=开始录指令；其它=唤醒词后直接说的指令。 */
  private static String pendingWake;
  private static final String K_VOICE_SEND = "floatwin_voice_autosend";

  static boolean voiceAutoSend(Context ctx) {
    return ctx != null && prefs(ctx).getBoolean(K_VOICE_SEND, false);
  }

  /** 设置页 / 通知栏开关语音唤醒。开要经透明壳拿前台身份启动 microphone 前台服务。 */
  static void setWakeEnabled(Context context, boolean on) {
    final Context app = context.getApplicationContext();
    wakeCtx = app;
    main.post(
        () -> {
          if (!on) {
            prefs(app).edit().putBoolean(FloatWake.K_WAKE, false).apply();
            FloatWake.stop();
            if (!recording && !micStarting) FloatMicService.stop(app);
            refreshSettings(app);
            if (web != null && !realPage) pushPayload();
            return;
          }
          prefs(app).edit().putBoolean(FloatWake.K_WAKE, true).apply();
          launchMicShell(app, FloatMicService.MODE_WAKE);
        });
  }

  /** 唤醒开着但服务没在跑（被系统回收 / 重启过）：用户打开悬浮窗时补起来。 */
  private static void ensureWake(Context app) {
    if (!FloatWake.enabled(app) || FloatWake.isRunning()) return;
    if (recording || micStarting) return;
    wakeCtx = app;
    launchMicShell(app, FloatMicService.MODE_WAKE);
  }

  private static void launchMicShell(Context app, String mode) {
    try {
      app.startActivity(
          new android.content.Intent(app, FloatMicActivity.class)
              .putExtra(FloatMicService.EXTRA_MODE, mode)
              .addFlags(
                  android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                      | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION
                      | android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
    } catch (Throwable error) {
      if (FloatMicService.MODE_WAKE.equals(mode)) onWakeService(false);
    }
  }

  static void onWakeService(boolean ok) {
    main.post(
        () -> {
          FloatMicActivity.close();
          Context app = anyCtx();
          if (app == null) return;
          if (!ok) {
            prefs(app).edit().putBoolean(FloatWake.K_WAKE, false).apply();
            toast(app, "语音唤醒启动失败（需要麦克风权限）");
          } else {
            toast(app, "语音唤醒已开启：说「" + FloatWake.words(app) + "」");
          }
          refreshSettings(app);
          if (web != null && !realPage) pushPayload();
        });
  }

  /** 听到唤醒词：提示音 + 震动，打开悬浮窗，然后录指令或直接用唤醒词后面的话。 */
  static void onWake(Context app, String command) {
    wakeCtx = app;
    if (recording || micStarting) return;
    try {
      android.media.ToneGenerator tone =
          new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70);
      tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 160);
      main.postDelayed(tone::release, 400);
    } catch (Throwable ignored) {
    }
    try {
      android.os.Vibrator vib = app.getSystemService(android.os.Vibrator.class);
      if (vib != null) vib.vibrate(android.os.VibrationEffect.createOneShot(40, 120));
    } catch (Throwable ignored) {
    }
    pendingWake = command == null ? "" : command;
    // 先给一个不依赖悬浮窗的回执：窗口没弹出来时也能知道「听到了」
    toast(app, pendingWake.isEmpty() ? "已唤醒，请说话" : "已唤醒：" + pendingWake);
    if (web != null && !realPage) {
      main.postDelayed(FloatWindow::runPendingWake, 260);
      return;
    }
    // 窗口没开或是完整档（dsh 页面没有这套语音界面）：开迷你/标准自绘窗，页面就绪后接着做
    if (realPage) prefs(app).edit().putInt(K_TIER, 1).apply();
    userClosed = false;
    showCustom(app);
  }

  private static void runPendingWake() {
    String command = pendingWake;
    pendingWake = null;
    if (command == null || web == null || realPage) return;
    if (command.isEmpty()) {
      startVoice();
    } else {
      evaluate(
          "window.dshFloat&&window.dshFloat.onVoice("
              + JSONObject.quote(command)
              + ","
              + (voiceAutoSend(anyCtx()) ? "true" : "false")
              + ")");
    }
  }

  /** 设置页开着就重建一次，让开关显示最新状态。 */
  private static void refreshSettings(Context app) {
    if (settingsPanel == null) return;
    closeSettings();
    toggleSettings(app);
  }

  private static void beginRecording() {
    Context ctx = web == null ? null : web.getContext();
    if (ctx == null) {
      stopMicService();
      return;
    }
    FloatWake.pause();
    if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      stopMicService();
      evaluate("window.dshFloat&&window.dshFloat.onVoiceError('没有麦克风权限')");
      return;
    }
    try {
      int rate = 16000;
      int min = android.media.AudioRecord.getMinBufferSize(
          rate,
          android.media.AudioFormat.CHANNEL_IN_MONO,
          android.media.AudioFormat.ENCODING_PCM_16BIT);
      final android.media.AudioRecord rec =
          new android.media.AudioRecord(
              android.media.MediaRecorder.AudioSource.MIC,
              rate,
              android.media.AudioFormat.CHANNEL_IN_MONO,
              android.media.AudioFormat.ENCODING_PCM_16BIT,
              Math.max(min, rate));
      if (rec.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
        rec.release();
        stopMicService();
        evaluate("window.dshFloat&&window.dshFloat.onVoiceError('麦克风被占用，录音启动失败')");
        return;
      }
      rec.startRecording();
      final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
      final long run = ++voiceRun;
      recorder = rec;
      pcm = buf;
      recording = true;
      voicePendingSeq = 0;
      evaluate("window.dshFloat&&window.dshFloat.onVoiceState('listening')");
      Thread thread =
          new Thread(
              () -> {
                byte[] buffer = new byte[1600]; // 50ms
                long lastPush = 0;
                double noise = -1;
                boolean spoke = false;
                boolean heard = false;
                long silentMs = 0;
                String autoStop = null;
                while (recording && voiceRun == run) {
                  int read;
                  try {
                    read = rec.read(buffer, 0, buffer.length);
                  } catch (Throwable error) {
                    read = -1;
                  }
                  if (read <= 0) break;
                  synchronized (buf) {
                    buf.write(buffer, 0, read);
                  }
                  long sum = 0;
                  int n = read / 2;
                  for (int i = 0; i + 1 < read; i += 2) {
                    int v = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                    sum += (long) v * v;
                  }
                  double rms = n == 0 ? 0 : Math.sqrt((double) sum / n);
                  if (sum > 0) heard = true;
                  long ms = buf.size() * 1000L / (16000 * 2);
                  // 前 0.4 秒估环境噪声，之后缓慢跟随较低值
                  if (ms <= 400) noise = noise < 0 ? rms : Math.min(noise, rms) * 0.5 + noise * 0.5;
                  else if (rms < noise) noise = noise * 0.9 + rms * 0.1;
                  double gate = Math.max(450, Math.max(noise, 0) * 2.6);
                  boolean loud = rms > gate;
                  if (loud) {
                    spoke = true;
                    silentMs = 0;
                  } else if (ms > 400) {
                    silentMs += read * 1000L / (16000 * 2);
                  }
                  double level = Math.max(0, Math.min(1, (Math.log10(1 + rms) - 2.2) / 1.6));
                  if (ms - lastPush >= 90) {
                    lastPush = ms;
                    final String js =
                        "window.dshFloat&&window.dshFloat.onVoiceLevel&&window.dshFloat.onVoiceLevel("
                            + String.format(java.util.Locale.ROOT, "%.3f", level) + "," + ms + ","
                            + (loud ? "true" : "false") + "," + (spoke ? "true" : "false") + ")";
                    main.post(() -> { if (voiceRun == run && recording) evaluate(js); });
                  }
                  if (!heard && ms >= 1500) { autoStop = "muted"; break; }
                  if (spoke && silentMs >= 1800) { autoStop = "silence"; break; }
                  if (!spoke && ms >= 8000) { autoStop = "nothing"; break; }
                  if (ms >= 40000) { autoStop = "limit"; break; }
                }
                final String why = autoStop;
                if (why != null) {
                  main.post(
                      () -> {
                        if (voiceRun != run || !recording) return;
                        if ("muted".equals(why)) {
                          cancelVoice();
                          evaluate("window.dshFloat&&window.dshFloat.onVoiceError('麦克风没有声音（被系统静音或被其他应用占用）')");
                        } else if ("nothing".equals(why)) {
                          cancelVoice();
                          evaluate("window.dshFloat&&window.dshFloat.onVoiceError('没有听到声音，已停止')");
                        } else {
                          stopAndTranscribe();
                        }
                      });
                }
              },
              "float-voice");
      thread.setDaemon(true);
      thread.start();
    } catch (Throwable error) {
      recording = false;
      stopMicService();
      evaluate("window.dshFloat&&window.dshFloat.onVoiceError('录音启动失败')");
    }
  }

  /** 关掉麦克风；不转写。 */
  private static void releaseRecorder() {
    recording = false;
    micStarting = false;
    voiceRun++;
    stopMicService();
    android.media.AudioRecord rec = recorder;
    recorder = null;
    try {
      if (rec != null) {
        rec.stop();
        rec.release();
      }
    } catch (Throwable ignored) {
    }
  }

  /** 取消录音或丢弃正在转写的结果。 */
  private static void cancelVoice() {
    releaseRecorder();
    pcm = null;
    voicePendingSeq = 0;
    evaluate("window.dshFloat&&window.dshFloat.onVoiceState('idle')");
  }

  /** 停止录音，把 PCM 包成 WAV 交给插件转写。 */
  private static void stopAndTranscribe() {
    Context ctx = web == null ? null : web.getContext();
    if (ctx == null) return;
    java.io.ByteArrayOutputStream buf = pcm;
    releaseRecorder();
    pcm = null;
    final byte[] pcmBytes;
    if (buf == null) {
      pcmBytes = new byte[0];
    } else {
      synchronized (buf) {
        pcmBytes = buf.toByteArray();
      }
    }
    if (pcmBytes.length < 16000 * 2 / 2) {
      evaluate("window.dshFloat&&window.dshFloat.onVoiceError('说话时间太短')");
      return;
    }
    final long seq = System.currentTimeMillis();
    voiceSeq = seq;
    voicePendingSeq = seq;
    evaluate("window.dshFloat&&window.dshFloat.onVoiceState('transcribing')");
    main.postDelayed(
        () -> {
          if (voicePendingSeq != seq) return;
          voicePendingSeq = 0;
          evaluate("window.dshFloat&&window.dshFloat.onVoiceError('识别超时，请重试')");
        },
        45000);
    Thread writer =
        new Thread(
            () -> {
              try {
                java.io.ByteArrayOutputStream wav = new java.io.ByteArrayOutputStream();
                int dataLen = pcmBytes.length;
                int rate = 16000;
                wav.write("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                writeLe32(wav, 36 + dataLen);
                wav.write("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                wav.write("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                writeLe32(wav, 16);
                writeLe16(wav, 1);
                writeLe16(wav, 1);
                writeLe32(wav, rate);
                writeLe32(wav, rate * 2);
                writeLe16(wav, 2);
                writeLe16(wav, 16);
                wav.write("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                writeLe32(wav, dataLen);
                wav.write(pcmBytes, 0, dataLen);
                String audio =
                    android.util.Base64.encodeToString(wav.toByteArray(), android.util.Base64.NO_WRAP);
                File file = guestFile(ctx, GUEST_VOICE);
                if (file == null || file.getParentFile() == null) throw new java.io.IOException("no guest");
                file.getParentFile().mkdirs();
                File tmp = new File(file.getPath() + ".tmp");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                  out.write(
                      new JSONObject().put("seq", seq).put("audio", audio).toString()
                          .getBytes(StandardCharsets.UTF_8));
                  out.flush();
                  out.getFD().sync();
                }
                if (!tmp.renameTo(file)) tmp.renameTo(file);
              } catch (Throwable error) {
                main.post(
                    () -> {
                      if (voicePendingSeq != seq) return;
                      voicePendingSeq = 0;
                      evaluate("window.dshFloat&&window.dshFloat.onVoiceError('语音发送失败')");
                    });
              }
            },
            "float-voice-upload");
    writer.setDaemon(true);
    writer.start();
  }

  private static void writeLe32(java.io.ByteArrayOutputStream out, int value) {
    out.write(value & 0xFF);
    out.write((value >> 8) & 0xFF);
    out.write((value >> 16) & 0xFF);
    out.write((value >> 24) & 0xFF);
  }

  private static void writeLe16(java.io.ByteArrayOutputStream out, int value) {
    out.write(value & 0xFF);
    out.write((value >> 8) & 0xFF);
  }

  /** 插件转写完成的文本；没有新结果就什么都不做。 */
  private static void pollVoiceResult(Context ctx) {
    File result = guestFile(ctx, GUEST_VOICE_RESULT);
    if (result == null || !result.isFile()) return;
    long stamp = result.lastModified();
    if (stamp == lastVoiceResult) return;
    lastVoiceResult = stamp;
    String json = readText(result);
    if (json == null || json.isEmpty()) return;
    try {
      JSONObject data = new JSONObject(json);
      long seq = data.optLong("seq", 0);
      // 只认当前在等的那次；取消过或超时的结果直接丢掉
      if (voicePendingSeq == 0 || (seq != 0 && seq != voicePendingSeq)) return;
      voicePendingSeq = 0;
      String text = data.optString("text", "").trim();
      String error = data.optString("error", "");
      if (error.isEmpty() && text.isEmpty()) {
        evaluate("window.dshFloat&&window.dshFloat.onVoiceError('没听清，再说一次')");
      } else if (!error.isEmpty()) {
        evaluate("window.dshFloat&&window.dshFloat.onVoiceError(" + JSONObject.quote(error) + ")");
      } else if (!text.isEmpty()) {
        evaluate(
            "window.dshFloat&&window.dshFloat.onVoice("
                + JSONObject.quote(text)
                + ","
                + (voiceAutoSend(ctx) ? "true" : "false")
                + ")");
      }
    } catch (Throwable ignored) {
    }
  }

  /** 选一张图，写进容器后把路径交给页面当附件引用。 */
  private static void pickImage() {
    Context ctx = web == null ? null : web.getContext();
    if (ctx == null) return;
    pendingImage = true;
    try {
      android.content.Intent intent =
          new android.content.Intent(ctx, FloatPickerActivity.class)
              .putExtra(FloatPickerActivity.EXTRA_TYPE, "image/*")
              .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
      ctx.startActivity(intent);
    } catch (Throwable error) {
      pendingImage = false;
    }
  }

  /** 选任意文件，写进容器后交给页面当附件。 */
  private static void pickFile() {
    Context ctx = web == null ? null : web.getContext();
    if (ctx == null) return;
    pendingFile = true;
    try {
      android.content.Intent intent =
          new android.content.Intent(ctx, FloatPickerActivity.class)
              .putExtra(FloatPickerActivity.EXTRA_TYPE, "*/*")
              .putExtra(FloatPickerActivity.EXTRA_FILE_MODE, true)
              .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
      ctx.startActivity(intent);
    } catch (Throwable error) {
      pendingFile = false;
    }
  }

  static void deliverPickedFileAttachment(android.net.Uri uri) {
    pendingFile = false;
    Context ctx = web == null ? null : web.getContext();
    if (uri == null || ctx == null) return;
    try {
      File attach = guestFile(ctx, "float-attach");
      if (attach == null) return;
      if (!attach.isDirectory() && !attach.mkdirs()) return;
      String name = "file-" + System.currentTimeMillis();
      String last = uri.getLastPathSegment();
      if (last != null && last.contains(".")) name += last.substring(last.lastIndexOf('.'));
      File target = new File(attach, name);
      try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
          java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
        if (in == null) return;
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      }
      evaluate(
          "window.dshFloat&&window.dshFloat.onFile("
              + JSONObject.quote("/root/.dsh/float-attach/" + target.getName())
              + ","
              + JSONObject.quote(target.getName())
              + ")");
    } catch (Throwable ignored) {
    }
  }

  static void deliverPickedImage(android.net.Uri uri) {
    pendingImage = false;
    Context ctx = web == null ? null : web.getContext();
    if (uri == null || ctx == null) return;
    try {
      File attach = guestFile(ctx, "float-attach");
      if (attach == null) return;
      if (!attach.isDirectory() && !attach.mkdirs()) return;
      File target = new File(attach, "img-" + System.currentTimeMillis() + ".jpg");
      try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
          java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
        if (in == null) return;
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      }
      String dataUrl = "";
      if (target.length() > 0 && target.length() <= 1800000L) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(target)) {
          byte[] bytes = new byte[(int) target.length()];
          int read = 0;
          while (read < bytes.length) {
            int step = in.read(bytes, read, bytes.length - read);
            if (step <= 0) break;
            read += step;
          }
          dataUrl =
              "data:image/jpeg;base64,"
                  + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
        } catch (Throwable ignored) {
        }
      }
      evaluate(
          "window.dshFloat&&window.dshFloat.onImage("
              + JSONObject.quote("/root/.dsh/float-attach/" + target.getName())
              + ","
              + JSONObject.quote(dataUrl)
              + ")");
    } catch (Throwable ignored) {
    }
  }

  private static void evaluate(String js) {
    try {
      if (web != null) web.evaluateJavascript(js, null);
    } catch (Throwable ignored) {
    }
  }

  private static void dismiss() {
    if (pendingBlur != null) {
      main.removeCallbacks(pendingBlur);
      pendingBlur = null;
    }
    releaseRecorder();
    pcm = null;
    voicePendingSeq = 0;
    if (web != null) userClosed = true;
    realPage = false;
    pendingFileChooser = null;
    moving = false;
    resizing = false;
    dragMoved = false;
    if (container != null && wm != null) {
      try {
        wm.removeViewImmediate(container);
      } catch (Throwable ignored) {
      }
    }
    if (web != null) {
      try {
        web.destroy();
      } catch (Throwable ignored) {
      }
    }
    settingsPanel = null;
    container = null;
    web = null;
    wm = null;
    params = null;
  }

  /** 文件选择器返回：把 Uri 交回 WebView 的上传回调。 */
  static void deliverPickedFile(android.net.Uri uri) {
    android.webkit.ValueCallback<android.net.Uri[]> callback = pendingFileChooser;
    pendingFileChooser = null;
    if (callback == null) return;
    try {
      callback.onReceiveValue(uri == null ? null : new android.net.Uri[] {uri});
    } catch (Throwable ignored) {
    }
  }

  private static int screenW(Context ctx) {
    return ctx.getResources().getDisplayMetrics().widthPixels;
  }

  private static int screenH(Context ctx) {
    return ctx.getResources().getDisplayMetrics().heightPixels;
  }

  private static int clamp(int value, int min, int max) {
    if (max < min) return min;
    return Math.max(min, Math.min(max, value));
  }

  private static int dp(Context ctx, int v) {
    return Math.round(v * ctx.getResources().getDisplayMetrics().density);
  }

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE);
  }

  private static String readAsset(Context context, String path) {
    try (java.io.InputStream in = context.getAssets().open(path);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException error) {
      return "<html><body style='color:#fff'>floatwin missing</body></html>";
    }
  }
}
