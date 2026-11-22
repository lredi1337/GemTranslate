package app.aitranslator.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/** Нажатие на иконку: открыть чат с переводчиком в Gemini; кнопки «Открыть» и «Развернуть» нажмёт служба */
public class LaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String url = Prefs.url(this);
        if (url.isEmpty()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        // Разрешение из ADB есть: служба включается только на время запуска и потом выключается сама
        if (Prefs.canToggleService(this)) {
            boolean wasOn = Prefs.serviceEnabled(this);
            Prefs.get(this).edit().putBoolean(Prefs.AUTO_MANAGED, !wasOn || Prefs.get(this).getBoolean(Prefs.AUTO_MANAGED, false)).apply();
            if (!wasOn) Prefs.enableService(this);
            waitForServiceThenOpen(url, System.currentTimeMillis());
            return;
        }
        launchNow(url);
    }

    /** Служба подключается не мгновенно — ждём её (до 2 с), чтобы заставка закрыла чат с первого кадра */
    private void waitForServiceThenOpen(String url, long startedAt) {
        if (AutoClickService.instance != null || System.currentTimeMillis() - startedAt > 2000) {
            launchNow(url);
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> waitForServiceThenOpen(url, startedAt), 50);
    }

    private void launchNow(String url) {
        if (Prefs.serviceEnabled(this)) {
            Prefs.arm(this);
            // Заставка закрывает чат Gemini, пока служба нажимает кнопки, — видна только «загрузка»
            AutoClickService svc = AutoClickService.instance;
            if (svc != null) svc.showSplash();
        } else {
            Toast.makeText(this, "Автонажатие выключено — включите в настройках (долгое нажатие на иконку)", Toast.LENGTH_LONG).show();
        }
        if (!openChat(this, url)) {
            Toast.makeText(this, "Не удалось открыть ссылку — проверьте её в настройках", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SetupActivity.class));
        }
        finish();
        overridePendingTransition(0, 0);
    }

    /** Открыть ссылку в приложении Gemini, а если его нет — чем откроется */
    static boolean openChat(Context c, String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setPackage(Prefs.GEMINI_PACKAGES[0]);
        try {
            c.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException ignored) {
            // Gemini не принимает эту ссылку напрямую — пусть система выберет
        }
        intent.setPackage(null);
        try {
            c.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }
}
