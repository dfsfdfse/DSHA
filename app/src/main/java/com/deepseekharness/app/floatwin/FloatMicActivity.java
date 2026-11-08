package com.deepseekharness.app.floatwin;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * 悬浮窗语音的透明壳：让应用短暂处于前台，以便
 * ① 没有麦克风权限时直接弹系统授权框；
 * ② 启动 microphone 前台服务（后台启动会被系统拒绝或拿到静音数据）。
 * 录音开始后由 {@link FloatWindow} 关闭本页；外部触摸照常穿透到下面的应用。
 */
public final class FloatMicActivity extends Activity {
  private static final int REQUEST = 43;
  static FloatMicActivity current;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    current = this;
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
    getWindow().setLayout(1, 1);
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST);
      return;
    }
    startMic();
  }

  private void startMic() {
    try {
      startForegroundService(new Intent(this, FloatMicService.class));
    } catch (Throwable error) {
      FloatWindow.onMicService(false);
      finish();
    }
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] perms, int[] results) {
    if (request != REQUEST) return;
    if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
      startMic();
    } else {
      FloatWindow.onMicDenied();
      finish();
    }
  }

  @Override
  protected void onDestroy() {
    if (current == this) current = null;
    super.onDestroy();
  }

  /** 录音已开始（或放弃），关掉透明页。 */
  static void close() {
    FloatMicActivity a = current;
    current = null;
    if (a != null) {
      try {
        a.finish();
        a.overridePendingTransition(0, 0);
      } catch (Throwable ignored) {
      }
    }
  }
}
