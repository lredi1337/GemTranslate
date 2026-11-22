package app.aitranslator.launcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Настройка: ссылка на чат, включение службы, подписи кнопок и что служба видела в последний раз */
public class SetupActivity extends Activity {
    private EditText url;
    private EditText openLabels;
    private EditText expandLabels;
    private EditText allowLabels;
    private CheckBox autoPerms;
    private TextView serviceStatus;
    private TextView lastRun;
    private LinearLayout box;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences p = Prefs.get(this);

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);

        header("1. Ссылка на чат с переводчиком");
        text("Откройте чат с Canvas переводчика на gemini.google.com в браузере и скопируйте адрес страницы "
                + "(вида gemini.google.com/app/…). Подойдёт и ссылка, которой вы открывали переводчик раньше.");
        url = field(p.getString(Prefs.URL, ""), "https://gemini.google.com/app/…", InputType.TYPE_TEXT_VARIATION_URI);
        button("Вставить из буфера", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = cm == null ? null : cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                url.setText(clip.getItemAt(0).getText().toString().trim());
            } else {
                toast("Буфер пуст");
            }
        });

        header("2. Автонажатие «Открыть» и «Развернуть»");
        serviceStatus = text("");
        text("Включите службу «Переводчик: автооткрытие» в разделе «Специальные возможности» → «Установленные приложения». "
                + "Она видит только приложение Gemini и работает 20 секунд после нажатия на иконку.");
        button("Открыть «Специальные возможности»", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        text("Если переключатель серый («Запрещённые настройки»): откройте сведения о приложении, "
                + "нажмите ⋮ вверху справа → «Разрешить запрещённые настройки», затем включите службу снова.");
        button("Открыть сведения о приложении", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));

        header("3. Подписи кнопок в Gemini");
        text("Через запятую. Если Gemini переименует кнопку, впишите новую подпись — её можно взять из списка «Что служба видела» ниже.");
        text("Кнопка, открывающая Canvas:");
        openLabels = field(p.getString(Prefs.OPEN_LABELS, Prefs.DEFAULT_OPEN), "", InputType.TYPE_CLASS_TEXT);
        text("Кнопка, разворачивающая на весь экран:");
        expandLabels = field(p.getString(Prefs.EXPAND_LABELS, Prefs.DEFAULT_EXPAND), "", InputType.TYPE_CLASS_TEXT);

        autoPerms = new CheckBox(this);
        autoPerms.setText("После разворачивания сразу давать переводчику доступ к микрофону и камере");
        autoPerms.setChecked(p.getBoolean(Prefs.AUTO_PERMS, true));
        box.addView(autoPerms);
        text("Кнопка в окне «Требуется разрешение»:");
        allowLabels = field(p.getString(Prefs.ALLOW_LABELS, Prefs.DEFAULT_ALLOW), "", InputType.TYPE_CLASS_TEXT);
        text("Для этого в Canvas должна быть свежая версия переводчика (со скрытой кнопкой «Подготовить микрофон и камеру»).");

        header("4. Последний запуск");
        lastRun = text("");

        Button save = button("Сохранить и открыть переводчик", v -> {
            if (!save()) return;
            startActivity(new Intent(this, LaunchActivity.class));
            finish();
        });
        save.setTypeface(Typeface.DEFAULT_BOLD);
        button("Сохранить", v -> { if (save()) toast("Сохранено"); });
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = Prefs.serviceEnabled(this);
        serviceStatus.setText(on ? "✓ Служба включена" : "✗ Служба выключена — кнопки нажимать будет некому");
        serviceStatus.setTypeface(Typeface.DEFAULT_BOLD);
        SharedPreferences p = Prefs.get(this);
        String seen = p.getString(Prefs.SEEN, "");
        lastRun.setText(p.getString(Prefs.LAST_RESULT, "Ещё не запускали")
                + (seen.isEmpty() ? "" : "\n\nЧто служба видела (кнопки и подписи в Gemini):\n" + seen));
    }

    private boolean save() {
        String u = url.getText().toString().trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            toast("Вставьте ссылку, которая начинается с https://");
            return false;
        }
        Prefs.get(this).edit()
                .putString(Prefs.URL, u)
                .putString(Prefs.OPEN_LABELS, openLabels.getText().toString())
                .putString(Prefs.EXPAND_LABELS, expandLabels.getText().toString())
                .putString(Prefs.ALLOW_LABELS, allowLabels.getText().toString())
                .putBoolean(Prefs.AUTO_PERMS, autoPerms.isChecked())
                .apply();
        return true;
    }

    private TextView header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
        box.addView(t);
        return t;
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(0, dp(4), 0, dp(4));
        t.setTextIsSelectable(true);
        box.addView(t);
        return t;
    }

    private EditText field(String value, String hint, int variation) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        e.setSingleLine(false);
        box.addView(e);
        return e;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        box.addView(b);
        return b;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
