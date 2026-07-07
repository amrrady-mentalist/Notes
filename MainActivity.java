package com.notesapp.offline;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import androidx.activity.OnBackPressedCallback;
import androidx.core.splashscreen.SplashScreen;
import com.getcapacitor.BridgeActivity;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BridgeActivity {

  private volatile boolean atRootScreen = true;

  /* Held true until the WebView tells us (via NativeNav.setContentReady) that
     it has actually painted its first real frame, or until the safety timeout
     below fires -- whichever happens first. This is what the splash screen's
     keepOnScreenCondition checks, so the splash never disappears into a blank
     gap before there's real content behind it, and never gets stuck forever
     if that signal is ever missed. */
  private volatile boolean contentReady = false;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    // Best-effort: hold the splash screen open until content is ready. This
    // depends on the app's theme being set up in a specific way, which this
    // code can't fully verify ahead of time -- if anything about it doesn't
    // match, skip it quietly rather than crashing the whole app on launch.
    try {
      SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
      splashScreen.setKeepOnScreenCondition(() -> !contentReady);
      new Handler(Looper.getMainLooper()).postDelayed(this::markContentReady, 1500);
    } catch (Throwable t) {
      contentReady = true;
    }

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

  /** Marks content as ready (unblocking the splash's keepOnScreenCondition, if
   *  it's active) and clears any leftover splash-themed window background, so
   *  nothing splash-shaped can remain visible once the real app is showing --
   *  regardless of exactly why a gap might otherwise appear. */
  private void markContentReady() {
    contentReady = true;
    try {
      getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    } catch (Throwable ignored) {}
  }

  /** Exposes NativeNav.setRootScreen(bool) / setContentReady() to JS: the former
   *  tells native code whether it's safe to minimize on back (or whether JS
   *  should handle it by closing an open editor/drawing/settings screen), the
   *  latter tells it the first real frame has painted so the splash can close. */
  private void setupNativeNavBridge() {
    bridge.getWebView().addJavascriptInterface(new Object() {
      @JavascriptInterface
      public void setRootScreen(boolean isRoot) {
        atRootScreen = isRoot;
      }

      @JavascriptInterface
      public void setContentReady() {
        runOnUiThread(MainActivity.this::markContentReady);
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
    // setDecorFitsSystemWindows(false) above only tells the *window* to draw
    // edge-to-edge; if the WebView (or a parent of it) still has its own
    // fitsSystemWindows=true, that view will keep insetting itself below the
    // status bar regardless, leaving a gap above it that exposes the window's
    // own background (this was the visible leftover splash-shaped strip at
    // the top of the screen). Force it off the whole way up the view chain.
    try {
      View v = bridge.getWebView();
      while (v != null) {
        v.setFitsSystemWindows(false);
        v = (v.getParent() instanceof View) ? (View) v.getParent() : null;
      }
    } catch (Throwable ignored) {}
  }
}
