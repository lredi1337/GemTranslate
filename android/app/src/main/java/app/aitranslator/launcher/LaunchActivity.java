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
        if (Prefs.serviceEnabled(this)) {
            Prefs.arm(this);
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
