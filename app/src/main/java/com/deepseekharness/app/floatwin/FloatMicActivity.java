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
 * 服务就绪后由 {@link FloatWindow} 关闭本页；外部触摸照常穿透到下面的应用。
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

  private boolean wakeMode() {
    String mode = getIntent() == null ? null : getIntent().getStringExtra(FloatMicService.EXTRA_MODE);
    return FloatMicService.MODE_WAKE.equals(mode);
  }

  private void fail(boolean denied) {
    if (wakeMode()) FloatWindow.onWakeService(false);
    else if (denied) FloatWindow.onMicDenied();
    else FloatWindow.onMicService(false);
    finish();
  }

  private void startMic() {
    try {
      startForegroundService(
          new Intent(this, FloatMicService.class)
              .putExtra(
                  FloatMicService.EXTRA_MODE,
                  wakeMode() ? FloatMicService.MODE_WAKE : FloatMicService.MODE_REC));
    } catch (Throwable error) {
      fail(false);
    }
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] perms, int[] results) {
    if (request != REQUEST) return;
    if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
      startMic();
    } else {
      fail(true);
    }
  }

  @Override
  protected void onDestroy() {
    if (current == this) current = null;
    super.onDestroy();
  }

  /** 服务已就绪（或放弃），关掉透明页。 */
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
