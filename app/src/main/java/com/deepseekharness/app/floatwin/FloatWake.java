package com.deepseekharness.app.floatwin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * 语音唤醒（“呼叫cc”）：在 microphone 前台服务里常驻监听。
 *
 * <p>没有专门的唤醒词模型：用能量检测切出 0.35–5 秒的短语音段，交给容器里 dsh 自己的
 * speechToText（本地 SenseVoice，不上网）转写，文本里含唤醒词就唤醒。唤醒词后面紧跟的话
 * （“呼叫cc 帮我看看天气”）直接当成指令，不用再录一次。
 *
 * <p>与悬浮窗录音共用麦克风：录指令时 {@link #pause()} 释放 AudioRecord，录完 {@link #resume()}。
 */
final class FloatWake {
  static final String K_WAKE = "floatwin_wake";
  static final String K_WAKE_WORDS = "floatwin_wake_words";
  static final String DEFAULT_WORDS = "呼叫cc";

  private static final String GUEST_IN = "float-wake.json";
  private static final String GUEST_OUT = "float-wake-result.json";
  private static final int RATE = 16000;
  private static final Handler main = new Handler(Looper.getMainLooper());

  private static volatile boolean running;
  private static volatile boolean paused;
  private static volatile long generation;
  private static Thread thread;
  private static android.media.AudioRecord recorder;
  private static android.os.PowerManager.WakeLock wakeLock;

  private FloatWake() {}

  static boolean enabled(Context ctx) {
    return prefs(ctx).getBoolean(K_WAKE, false);
  }

  static String words(Context ctx) {
    String value = prefs(ctx).getString(K_WAKE_WORDS, DEFAULT_WORDS);
    return value == null || value.trim().isEmpty() ? DEFAULT_WORDS : value.trim();
  }

  static boolean isRunning() {
    return running;
  }

  /** 由 {@link FloatMicService} 在 startForeground 成功后调用。 */
  static synchronized void start(Context context) {
    if (running) return;
    final Context app = context.getApplicationContext();
    running = true;
    paused = false;
    final long gen = ++generation;
    try {
      android.os.PowerManager pm = app.getSystemService(android.os.PowerManager.class);
      if (pm != null && wakeLock == null) {
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "rcha:float-wake");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
      }
    } catch (Throwable ignored) {
    }
    thread = new Thread(() -> loop(app, gen), "float-wake");
    thread.setDaemon(true);
    thread.start();
  }

  static synchronized void stop() {
    running = false;
    generation++;
    closeRecorder();
    try {
      if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    } catch (Throwable ignored) {
    }
    wakeLock = null;
  }

  /** 悬浮窗要录指令：先把麦克风让出来。 */
  static void pause() {
    if (!running) return;
    paused = true;
    closeRecorder();
  }

  static void resume() {
    paused = false;
  }

  private static synchronized void closeRecorder() {
    android.media.AudioRecord rec = recorder;
    recorder = null;
    if (rec == null) return;
    try {
      rec.stop();
    } catch (Throwable ignored) {
    }
    try {
      rec.release();
    } catch (Throwable ignored) {
    }
  }

  private static synchronized android.media.AudioRecord openRecorder() {
    try {
      int min =
          android.media.AudioRecord.getMinBufferSize(
              RATE,
              android.media.AudioFormat.CHANNEL_IN_MONO,
              android.media.AudioFormat.ENCODING_PCM_16BIT);
      android.media.AudioRecord rec =
          new android.media.AudioRecord(
              android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
              RATE,
              android.media.AudioFormat.CHANNEL_IN_MONO,
              android.media.AudioFormat.ENCODING_PCM_16BIT,
              Math.max(min, RATE));
      if (rec.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
        rec.release();
        return null;
      }
      rec.startRecording();
      recorder = rec;
      return rec;
    } catch (Throwable error) {
      return null;
    }
  }

  private static void loop(Context app, long gen) {
    final int frameBytes = RATE * 2 * 30 / 1000; // 30ms
    final int preRollFrames = 10; // 300ms
    final int endSilenceFrames = 22; // ~660ms
    final int minFrames = 12; // 360ms
    final int maxFrames = 170; // ~5s
    byte[] frame = new byte[frameBytes];
    java.util.ArrayDeque<byte[]> preRoll = new java.util.ArrayDeque<>();
    java.io.ByteArrayOutputStream seg = null;
    int segFrames = 0;
    int silent = 0;
    int loudRun = 0;
    double noise = -1;
    long busyUntil = 0;
    while (running && generation == gen) {
      if (paused) {
        closeRecorder();
        seg = null;
        preRoll.clear();
        sleep(150);
        continue;
      }
      android.media.AudioRecord rec = recorder;
      if (rec == null) {
        rec = openRecorder();
        if (rec == null) {
          sleep(1000);
          continue;
        }
      }
      int read;
      try {
        read = rec.read(frame, 0, frame.length);
      } catch (Throwable error) {
        read = -1;
      }
      if (read <= 0) {
        if (!paused) {
          closeRecorder();
          sleep(300);
        }
        continue;
      }
      long sum = 0;
      for (int i = 0; i + 1 < read; i += 2) {
        int v = (short) ((frame[i] & 0xFF) | (frame[i + 1] << 8));
        sum += (long) v * v;
      }
      double rms = Math.sqrt((double) sum / Math.max(1, read / 2));
      if (noise < 0) noise = rms;
      double gate = Math.max(420, noise * 2.8);
      boolean loud = rms > gate;
      if (!loud && seg == null) noise = noise * 0.97 + rms * 0.03;
      byte[] copy = java.util.Arrays.copyOf(frame, read);
      if (seg == null) {
        preRoll.addLast(copy);
        while (preRoll.size() > preRollFrames) preRoll.removeFirst();
        loudRun = loud ? loudRun + 1 : 0;
        if (loudRun >= 3 && System.currentTimeMillis() >= busyUntil) {
          seg = new java.io.ByteArrayOutputStream();
          for (byte[] b : preRoll) seg.write(b, 0, b.length);
          segFrames = preRoll.size();
          preRoll.clear();
          silent = 0;
        }
        continue;
      }
      seg.write(copy, 0, copy.length);
      segFrames++;
      silent = loud ? 0 : silent + 1;
      if (silent >= endSilenceFrames || segFrames >= maxFrames) {
        byte[] pcm = seg.toByteArray();
        int speechFrames = segFrames - silent;
        seg = null;
        loudRun = 0;
        if (speechFrames >= minFrames) {
          // 转写期间不再切新段，避免把 SenseVoice 排满
          busyUntil = System.currentTimeMillis() + 60_000;
          String text = transcribe(app, pcm, gen);
          busyUntil = 0;
          if (text != null && running && generation == gen && !paused) handle(app, text);
        }
      }
    }
    closeRecorder();
  }

  /** 交给插件转写，最多等 10 秒。 */
  private static String transcribe(Context app, byte[] pcm, long gen) {
    try {
      File in = FloatWindow.guestFile(app, GUEST_IN);
      File out = FloatWindow.guestFile(app, GUEST_OUT);
      if (in == null || out == null || in.getParentFile() == null) return null;
      in.getParentFile().mkdirs();
      long seq = System.currentTimeMillis();
      String audio = android.util.Base64.encodeToString(wav(pcm), android.util.Base64.NO_WRAP);
      File tmp = new File(in.getPath() + ".tmp");
      try (java.io.FileOutputStream os = new java.io.FileOutputStream(tmp)) {
        os.write(new JSONObject().put("seq", seq).put("audio", audio).toString().getBytes(StandardCharsets.UTF_8));
      }
      if (!tmp.renameTo(in)) return null;
      long deadline = System.currentTimeMillis() + 10_000;
      while (System.currentTimeMillis() < deadline && running && generation == gen) {
        sleep(150);
        if (!out.isFile()) continue;
        String json = FloatWindow.readText(out);
        if (json == null || json.isEmpty()) continue;
        JSONObject data = new JSONObject(json);
        if (data.optLong("seq", 0) != seq) continue;
        return data.optString("text", "");
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  private static void handle(Context app, String text) {
    String command = match(text, words(app));
    if (command == null) return;
    final String cmd = command;
    main.post(() -> FloatWindow.onWake(app, cmd));
  }

  /** 唤醒词别名：英文字母常被识别成同音汉字。 */
  static List<String> aliases(String words) {
    List<String> out = new ArrayList<>();
    for (String raw : words.split("[,，|;；/\\n]+")) {
      String w = norm(raw);
      if (w.isEmpty()) continue;
      out.add(w);
      if (w.contains("cc")) {
        for (String alt : new String[] {"西西", "希希", "茜茜", "cici", "嘻嘻", "惜惜", "c c", "sisi", "溪溪"}) {
          out.add(w.replace("cc", norm(alt)));
        }
      }
    }
    return out;
  }

  /**
   * 文本里含唤醒词就返回唤醒词后面的话（可能为空串）；不含返回 null。
   * 比较时忽略大小写、空格和标点。
   */
  static String match(String text, String words) {
    if (text == null) return null;
    StringBuilder normalized = new StringBuilder();
    List<Integer> map = new ArrayList<>();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        normalized.append(Character.toLowerCase(c));
        map.add(i);
      }
    }
    String n = normalized.toString();
    for (String w : aliases(words)) {
      int at = n.indexOf(w);
      if (at < 0) continue;
      int end = at + w.length();
      if (end >= map.size()) return "";
      String rest = text.substring(map.get(end)).trim();
      return rest.replaceFirst("^[\\p{Punct}，。、！？：；\\s]+", "").trim();
    }
    return null;
  }

  private static String norm(String s) {
    StringBuilder b = new StringBuilder();
    for (char c : s.toCharArray()) {
      if (Character.isLetterOrDigit(c)) b.append(Character.toLowerCase(c));
    }
    return b.toString().toLowerCase(Locale.ROOT);
  }

  static byte[] wav(byte[] pcm) throws java.io.IOException {
    java.io.ByteArrayOutputStream wav = new java.io.ByteArrayOutputStream();
    int len = pcm.length;
    wav.write("RIFF".getBytes(StandardCharsets.US_ASCII));
    le32(wav, 36 + len);
    wav.write("WAVE".getBytes(StandardCharsets.US_ASCII));
    wav.write("fmt ".getBytes(StandardCharsets.US_ASCII));
    le32(wav, 16);
    le16(wav, 1);
    le16(wav, 1);
    le32(wav, RATE);
    le32(wav, RATE * 2);
    le16(wav, 2);
    le16(wav, 16);
    wav.write("data".getBytes(StandardCharsets.US_ASCII));
    le32(wav, len);
    wav.write(pcm, 0, len);
    return wav.toByteArray();
  }

  private static void le32(java.io.ByteArrayOutputStream o, int v) {
    o.write(v & 0xFF);
    o.write((v >> 8) & 0xFF);
    o.write((v >> 16) & 0xFF);
    o.write((v >> 24) & 0xFF);
  }

  private static void le16(java.io.ByteArrayOutputStream o, int v) {
    o.write(v & 0xFF);
    o.write((v >> 8) & 0xFF);
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException ignored) {
    }
  }

  private static SharedPreferences prefs(Context ctx) {
    return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE);
  }
}
