package app.aitranslator.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * После нажатия на ярлык (и только в течение 20 секунд) ищет в Gemini кнопку «Открыть» у Canvas,
 * нажимает её, затем «Развернуть». Видит только приложение Gemini (см. res/xml/auto_click.xml).
 */
public class AutoClickService extends AccessibilityService {
    private static final long MIN_GAP_AFTER_CLICK_MS = 700;
    /** Если «Открыть» не видно столько времени, Canvas, вероятно, уже открыт — можно сразу «Развернуть» */
    private static final long EXPAND_WITHOUT_OPEN_AFTER_MS = 6000;
    private static final int MAX_DEPTH = 60;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean scanPosted;
    private long session;          // момент запуска ярлыка, к которому относится текущее состояние
    private boolean openClicked;
    private String openedLabel = "";
    private long lastClickAt;
    private final Set<String> seen = new LinkedHashSet<>();

    private final Runnable scan = () -> {
        scanPosted = false;
        scanNow();
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (isArmed()) schedule(120);
    }

    @Override
    public void onInterrupt() {}

    private boolean isArmed() {
        return System.currentTimeMillis() < Prefs.get(this).getLong(Prefs.ARMED_UNTIL, 0);
    }

    private void schedule(long delayMs) {
        if (scanPosted) return;
        scanPosted = true;
        handler.postDelayed(scan, delayMs);
    }

    private void scanNow() {
        SharedPreferences p = Prefs.get(this);
        long now = System.currentTimeMillis();
        long until = p.getLong(Prefs.ARMED_UNTIL, 0);
        long armedAt = p.getLong(Prefs.ARMED_AT, 0);
        if (now >= until) {
            if (session != 0 && session == armedAt) {
                session = 0;
                if (p.getString(Prefs.LAST_RESULT, "").startsWith("Запущено")) {
                    Prefs.disarm(this, openClicked
                            ? "Нажато «" + openedLabel + "», но кнопка «Развернуть» не найдена — впишите её подпись из списка ниже"
                            : "Кнопки не найдены за 20 секунд — сверьте подписи со списком ниже");
                }
            }
            return;
        }
        if (session != armedAt) { // новый запуск ярлыка
            session = armedAt;
            openClicked = false;
            lastClickAt = 0;
            seen.clear();
        }
        if (now - lastClickAt < MIN_GAP_AFTER_CLICK_MS) {
            schedule(MIN_GAP_AFTER_CLICK_MS - (now - lastClickAt) + 50);
            return;
        }

        List<String> openLabels = Prefs.labels(this, Prefs.OPEN_LABELS, Prefs.DEFAULT_OPEN);
        List<String> expandLabels = Prefs.labels(this, Prefs.EXPAND_LABELS, Prefs.DEFAULT_EXPAND);
        Found found = new Found();
        for (AccessibilityNodeInfo root : roots()) walk(root, 0, openLabels, expandLabels, found);
        p.edit().putString(Prefs.SEEN, String.join(" · ", seen)).apply();

        boolean mayExpand = openClicked || (found.open == null && now - armedAt > EXPAND_WITHOUT_OPEN_AFTER_MS);
        if (found.expand != null && mayExpand) {
            if (click(found.expand)) {
                Prefs.disarm(this, (openClicked ? "Нажато «" + openedLabel + "», затем «" : "Нажато «") + labelOf(found.expand)
                        + "» за " + (now - armedAt) / 100 / 10.0 + " с");
                session = 0;
                return;
            }
        } else if (found.open != null && !openClicked) {
            if (click(found.open)) {
                openClicked = true;
                openedLabel = labelOf(found.open);
                lastClickAt = now;
                p.edit().putString(Prefs.LAST_RESULT, "Запущено: нажато «" + openedLabel + "», жду «Развернуть»…").apply();
            }
        }
        // События приходят не всегда — проверяем ещё раз, пока не истекло время
        schedule(400);
    }

    private static final class Found {
        AccessibilityNodeInfo open;    // последняя подходящая — у самого свежего Canvas в чате
        AccessibilityNodeInfo expand;  // первая подходящая
    }

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && isGemini(r.getPackageName())) out.add(r);
            }
        } catch (RuntimeException ignored) {}
        if (out.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && isGemini(r.getPackageName())) out.add(r);
        }
        return out;
    }

    private static boolean isGemini(CharSequence pkg) {
        if (pkg == null) return false;
        for (String g : Prefs.GEMINI_PACKAGES) if (g.contentEquals(pkg)) return true;
        return false;
    }

    private void walk(AccessibilityNodeInfo n, int depth, List<String> openLabels, List<String> expandLabels, Found found) {
        if (n == null || depth > MAX_DEPTH) return;
        if (n.isVisibleToUser() && !inTopBar(n)) {
            String label = labelOf(n);
            if (!label.isEmpty()) {
                if (label.length() <= 40 && (n.isClickable() || hasClickableParent(n)) && seen.size() < 60) seen.add(label);
                if (matches(label, openLabels)) found.open = n;
                if (found.expand == null && matches(label, expandLabels)) found.expand = n;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), depth + 1, openLabels, expandLabels, found);
    }

    /** Верхняя полоса экрана (меню, заголовок, «новый чат») — там нужных кнопок нет, ничего не нажимаем */
    private boolean inTopBar(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        return r.bottom > 0 && r.bottom < screenH * 0.12f;
    }

    private static String labelOf(AccessibilityNodeInfo n) {
        String t = Prefs.normalize(n.getText());
        if (!t.isEmpty()) return t;
        return Prefs.normalize(n.getContentDescription());
    }

    private static boolean hasClickableParent(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n.getParent();
        for (int i = 0; p != null && i < 3; i++, p = p.getParent()) if (p.isClickable()) return true;
        return false;
    }

    /**
     * Только точное совпадение подписи. Раньше подходило и начало подписи — и служба нажимала кнопку меню
     * «Открыть панель навигации» вместо «Открыть» у Canvas
     */
    private static boolean matches(String label, List<String> wanted) {
        if (NAV_WORDS.matcher(label).find()) return false;
        for (String w : wanted) if (label.equals(w)) return true;
        return false;
    }

    private static final java.util.regex.Pattern NAV_WORDS =
            java.util.regex.Pattern.compile("навигац|меню|navigation|menu|drawer|боков");

    private boolean click(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo c = node; c != null; c = c.getParent()) {
            if (c.isClickable()) {
                if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                break;
            }
        }
        // Не нажалось как кнопка — касаемся середины, как пальцем
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        Path path = new Path();
        path.moveTo(r.exactCenterX(), r.exactCenterY());
        GestureDescription tap = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        return dispatchGesture(tap, null, null);
    }
}
