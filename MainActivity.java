package com.notesapp.offline;

import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BridgeActivity {

  private volatile boolean atRootScreen = true;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    enableImmersiveMode();
    setupNativeNavBridge();
    setupGestureExclusion();
    setupBackHandling();
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) {
      enableImmersiveMode();
      setupGestureExclusion();
    }
  }

  /** Exposes NativeNav.setRootScreen(bool) to JS so the WebView can tell native
   *  code whether it's safe to minimize on back, or whether JS should handle it
   *  (closing an open editor/drawing/settings screen instead). */
  private void setupNativeNavBridge() {
    bridge.getWebView().addJavascriptInterface(new Object() {
      @JavascriptInterface
      public void setRootScreen(boolean isRoot) {
        atRootScreen = isRoot;
      }
    }, "NativeNav");
  }

  /** Intercepts both the hardware/gesture back action. At the root screen we
   *  minimize the app (moveTaskToBack) instead of killing the activity, which
   *  avoids the "swipe from the edge closes the app" behavior. Everywhere else
   *  we hand off to the web app's own close logic via window.AndroidBack(). */
  private void setupBackHandling() {
    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {
        if (atRootScreen) {
          moveTaskToBack(true);
        } else {
          bridge.getWebView().evaluateJavascript(
              "window.AndroidBack && window.AndroidBack();", null);
        }
      }
    });
  }

  /** Excludes thin strips along the left/right screen edges from Android's
   *  system edge-swipe-back gesture, so the app's own left/right swipe-between-
   *  tabs gesture reliably receives its touch events instead of the OS
   *  intercepting them as a back gesture (this was also the root cause of the
   *  "glitchy" swipe between tabs — some touches were being stolen by the OS). */
  private void setupGestureExclusion() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
    final View root = bridge.getWebView();
    root.post(new Runnable() {
      @Override
      public void run() {
        int height = root.getHeight();
        if (height <= 0) return;
        int width = root.getWidth();
        int exclusionWidthPx = (int) (32 * getResources().getDisplayMetrics().density);
        List<Rect> rects = new ArrayList<>();
        rects.add(new Rect(0, 0, exclusionWidthPx, height));
        rects.add(new Rect(width - exclusionWidthPx, 0, width, height));
        root.setSystemGestureExclusionRects(rects);
      }
    });
  }

  private void enableImmersiveMode() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      getWindow().setDecorFitsSystemWindows(false);
      WindowInsetsController controller = getWindow().getInsetsController();
      if (controller != null) {
        controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        controller.setSystemBarsBehavior(
            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      }
    } else {
      getWindow().getDecorView().setSystemUiVisibility(
          View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
              | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }
  }
}
