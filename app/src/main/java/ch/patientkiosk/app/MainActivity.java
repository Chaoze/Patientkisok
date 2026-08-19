package ch.patientkiosk.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String PREFS = "patient_kiosk";
    private static final String KEY_HOST = "host";
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_PIN_SALT = "pin_salt";
    private static final String KEY_TITLE = "title";

    private SharedPreferences prefs;
    private DevicePolicyManager dpm;
    private ComponentName admin;

    private LinearLayout root;
    private LinearLayout landing;
    private TextView titleView;
    private TextView statusView;
    private WebView webView;
    private ProgressBar progress;

    private boolean adminUnlocked = false;
    private int secretTaps = 0;
    private final Handler tapHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, KioskDeviceAdminReceiver.class);

        buildUi();
        configureWebView();

        if (isConfigured()) {
            applyTitle();
            enterPatientMode();
        } else {
            showFirstRunSetup();
        }
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 247, 245));
        root.setPadding(dp(26), dp(24), dp(26), dp(24));
        setContentView(root);

        landing = new LinearLayout(this);
        landing.setOrientation(LinearLayout.VERTICAL);
        landing.setGravity(Gravity.CENTER);
        root.addView(landing, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView logo = new TextView(this);
        logo.setText("✚");
        logo.setTextSize(54);
        logo.setTextColor(Color.rgb(30, 30, 30));
        logo.setGravity(Gravity.CENTER);
        landing.addView(logo, new LinearLayout.LayoutParams(dp(120), dp(120)));

        titleView = new TextView(this);
        titleView.setText("Anamnese");
        titleView.setTextSize(31);
        titleView.setTextColor(Color.rgb(24, 24, 24));
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        landing.addView(titleView);

        TextView subtitle = new TextView(this);
        subtitle.setText("QR-Code scannen und Formular ausfüllen");
        subtitle.setTextSize(16);
        subtitle.setTextColor(Color.rgb(100, 100, 100));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(10), 0, dp(28));
        landing.addView(subtitle);

        Button scan = new Button(this);
        scan.setText("QR-CODE SCANNEN");
        scan.setTextSize(18);
        scan.setAllCaps(false);
        scan.setOnClickListener(v -> launchScanner());
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(
                Math.min(dp(440), getResources().getDisplayMetrics().widthPixels - dp(70)), dp(66));
        landing.addView(scan, scanLp);

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setTextColor(Color.rgb(110, 110, 110));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(24), 0, 0);
        landing.addView(statusView);

        logo.setOnClickListener(v -> handleSecretTap());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        webView = new WebView(this);
        webView.setVisibility(View.GONE);
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setSaveFormData(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        webView.setOnLongClickListener(v -> true);
        webView.setHapticFeedbackEnabled(false);
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> blocked());

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (isAllowed(request.getUrl())) return false;
                blocked();
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (isAllowed(uri)) return false;
                blocked();
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
            }
        });
    }

    private void launchScanner() {
        if (!isConfigured()) {
            showFirstRunSetup();
            return;
        }
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("QR-Code der Anamnese scannen");
        integrator.setBeepEnabled(false);
        integrator.setBarcodeImageEnabled(false);
        integrator.setOrientationLocked(false);
        integrator.setCaptureActivity(KioskCaptureActivity.class);
        integrator.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) handleQr(result.getContents().trim());
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void handleQr(String raw) {
        Uri uri;
        try {
            uri = Uri.parse(raw);
        } catch (Exception e) {
            invalidQr("Ungültiger QR-Code.");
            return;
        }
        if (!isAllowed(uri)) {
            invalidQr("Dieser QR-Code gehört nicht zur freigegebenen Praxis-Webseite.");
            return;
        }
        landing.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(uri.toString());
    }

    private boolean isAllowed(Uri uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) return false;
        if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
        String allowed = configuredHost();
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return !allowed.isEmpty() && (host.equals(allowed) || host.endsWith("." + allowed));
    }

    private void invalidQr(String message) {
        new AlertDialog.Builder(this)
                .setTitle("QR-Code nicht erlaubt")
                .setMessage(message)
                .setPositiveButton("Nochmals scannen", (d, w) -> launchScanner())
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void blocked() {
        Toast.makeText(this, "Externe Webseiten sind gesperrt", Toast.LENGTH_SHORT).show();
    }

    private void showFirstRunSetup() {
        adminUnlocked = true;
        stopKiosk();
        showSystemUi();

        LinearLayout box = dialogBox();
        EditText title = input("Titel, z.B. Anamnese", InputType.TYPE_CLASS_TEXT);
        title.setText("Anamnese");
        EditText domain = input("Erlaubte Domain, z.B. app.denteo.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText pin = input("Admin-PIN (4–12 Ziffern)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        box.addView(title);
        box.addView(domain);
        box.addView(pin);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Patient Kiosk einrichten")
                .setMessage("Einmalig Praxis-Domain und Admin-PIN festlegen.")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Speichern", null)
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String host = normalizeHost(domain.getText().toString());
            String p = pin.getText().toString().trim();
            String t = title.getText().toString().trim();
            if (!validHost(host)) {
                domain.setError("Bitte nur eine gültige Domain eingeben");
                return;
            }
            if (!p.matches("\\d{4,12}")) {
                pin.setError("4 bis 12 Ziffern");
                return;
            }
            String salt = newSalt();
            prefs.edit()
                    .putString(KEY_HOST, host)
                    .putString(KEY_TITLE, t.isEmpty() ? "Anamnese" : t)
                    .putString(KEY_PIN_SALT, salt)
                    .putString(KEY_PIN_HASH, hashPin(p, salt))
                    .apply();
            dialog.dismiss();
            applyTitle();
            enterPatientMode();
        }));
        dialog.show();
    }

    private void handleSecretTap() {
        secretTaps++;
        tapHandler.removeCallbacksAndMessages(null);
        if (secretTaps >= 7) {
            secretTaps = 0;
            promptAdminPin();
        } else {
            tapHandler.postDelayed(() -> secretTaps = 0, 2200);
        }
    }

    private void promptAdminPin() {
        if (!isConfigured()) return;
        hideSystemUi();
        EditText pin = input("Admin-PIN", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Admin entsperren")
                .setView(pin)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Entsperren", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!verifyPin(pin.getText().toString().trim())) {
                pin.setError("PIN falsch");
                return;
            }
            adminUnlocked = true;
            stopKiosk();
            showSystemUi();
            dialog.dismiss();
            showAdminMenu();
        }));
        dialog.setOnDismissListener(d -> {
            if (!adminUnlocked) enterPatientMode();
        });
        dialog.show();
    }

    private void showAdminMenu() {
        stopKiosk();
        showSystemUi();
        String state = isDeviceOwner() ? "Echter Kioskmodus verfügbar" : "Soft-Fullscreen; Device Owner für vollständige Sperre erforderlich";
        new AlertDialog.Builder(this)
                .setTitle("Admin")
                .setMessage("Freigegeben: " + configuredHost() + "\n\n" + state)
                .setItems(new String[]{
                        "Neue Patientin / neuer Patient",
                        "QR-Code scannen",
                        "Einstellungen ändern",
                        "Patientenmodus sperren",
                        "App schließen"
                }, (d, which) -> {
                    if (which == 0) {
                        clearPatientSession();
                        showLanding();
                        enterPatientMode();
                    } else if (which == 1) {
                        enterPatientMode();
                        launchScanner();
                    } else if (which == 2) {
                        showSettings();
                    } else if (which == 3) {
                        enterPatientMode();
                    } else if (which == 4) {
                        finishAndRemoveTask();
                    }
                })
                .setOnCancelListener(d -> enterPatientMode())
                .show();
    }

    private void showSettings() {
        LinearLayout box = dialogBox();
        EditText title = input("Titel", InputType.TYPE_CLASS_TEXT);
        title.setText(prefs.getString(KEY_TITLE, "Anamnese"));
        EditText domain = input("Erlaubte Domain", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        domain.setText(configuredHost());
        EditText pin = input("Neuer PIN (leer = unverändert)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        box.addView(title);
        box.addView(domain);
        box.addView(pin);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Einstellungen")
                .setView(box)
                .setNegativeButton("Abbrechen", (d, w) -> showAdminMenu())
                .setPositiveButton("Speichern", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String host = normalizeHost(domain.getText().toString());
            String newPin = pin.getText().toString().trim();
            if (!validHost(host)) {
                domain.setError("Ungültige Domain");
                return;
            }
            if (!newPin.isEmpty() && !newPin.matches("\\d{4,12}")) {
                pin.setError("4 bis 12 Ziffern");
                return;
            }
            SharedPreferences.Editor e = prefs.edit()
                    .putString(KEY_HOST, host)
                    .putString(KEY_TITLE, title.getText().toString().trim().isEmpty() ? "Anamnese" : title.getText().toString().trim());
            if (!newPin.isEmpty()) {
                String salt = newSalt();
                e.putString(KEY_PIN_SALT, salt).putString(KEY_PIN_HASH, hashPin(newPin, salt));
            }
            e.apply();
            clearPatientSession();
            applyTitle();
            dialog.dismiss();
            showAdminMenu();
        }));
        dialog.show();
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        return box;
    }

    private EditText input(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        return e;
    }

    private boolean validHost(String host) {
        return !host.isEmpty() && !host.contains("/") && !host.contains(" ") && host.contains(".");
    }

    private String normalizeHost(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("https://")) s = s.substring(8);
        if (s.startsWith("http://")) s = s.substring(7);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private String configuredHost() {
        return normalizeHost(prefs.getString(KEY_HOST, ""));
    }

    private boolean isConfigured() {
        return !configuredHost().isEmpty() && prefs.contains(KEY_PIN_HASH) && prefs.contains(KEY_PIN_SALT);
    }

    private void applyTitle() {
        titleView.setText(prefs.getString(KEY_TITLE, "Anamnese"));
        statusView.setText(isDeviceOwner() ? "SICHERER KIOSKMODUS" : "GESCHÜTZTER PATIENTENMODUS");
    }

    private void clearPatientSession() {
        webView.stopLoading();
        webView.clearHistory();
        webView.clearCache(true);
        webView.clearFormData();
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
    }

    private void showLanding() {
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.setVisibility(View.GONE);
        landing.setVisibility(View.VISIBLE);
    }

    private void enterPatientMode() {
        adminUnlocked = false;
        hideSystemUi();
        startKioskIfPermitted();
    }

    private void startKioskIfPermitted() {
        if (!isConfigured() || adminUnlocked) return;
        try {
            if (isDeviceOwner()) {
                dpm.setLockTaskPackages(admin, new String[]{getPackageName()});
                dpm.setStatusBarDisabled(admin, true);
                dpm.setKeyguardDisabled(admin, true);
            }
            if (dpm.isLockTaskPermitted(getPackageName())) {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) startLockTask();
            }
        } catch (Exception ignored) { }
    }

    private void stopKiosk() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) stopLockTask();
            if (isDeviceOwner()) dpm.setStatusBarDisabled(admin, false);
        } catch (Exception ignored) { }
    }

    private boolean isDeviceOwner() {
        try {
            return dpm.isDeviceOwnerApp(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void showSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (adminUnlocked) {
            showAdminMenu();
        } else {
            Toast.makeText(this, "Patientenmodus ist gesperrt", Toast.LENGTH_SHORT).show();
            hideSystemUi();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!adminUnlocked && isConfigured()) enterPatientMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !adminUnlocked) hideSystemUi();
    }

    private String newSalt() {
        byte[] salt = new byte[24];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    private String hashPin(String pin, String saltText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Base64.getDecoder().decode(saltText));
            digest.update(pin.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean verifyPin(String pin) {
        String salt = prefs.getString(KEY_PIN_SALT, "");
        String expected = prefs.getString(KEY_PIN_HASH, "");
        if (salt.isEmpty() || expected.isEmpty()) return false;
        byte[] a = hashPin(pin, salt).getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
