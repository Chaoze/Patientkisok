package ch.patientkiosk.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class KioskCaptureActivity extends ComponentActivity {

    private static final int CAMERA_PERMISSION_REQUEST = 9102;

    private PreviewView previewView;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private MultiFormatReader reader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hideUi();
        buildUi();
        cameraExecutor = Executors.newSingleThreadExecutor();

        reader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        reader.setHints(hints);

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView heading = new TextView(this);
        heading.setText("QR-Code scannen");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(22);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(dp(16), dp(18), dp(16), dp(18));
        GradientDrawable headingBg = new GradientDrawable();
        headingBg.setColor(0x66000000);
        headingBg.setCornerRadius(dp(18));
        heading.setBackground(headingBg);
        FrameLayout.LayoutParams headingLp = new FrameLayout.LayoutParams(dp(260), dp(66));
        headingLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        headingLp.topMargin = dp(36);
        root.addView(heading, headingLp);

        View guide = new View(this);
        GradientDrawable guideBg = new GradientDrawable();
        guideBg.setColor(Color.TRANSPARENT);
        guideBg.setStroke(dp(3), Color.WHITE);
        guideBg.setCornerRadius(dp(26));
        guide.setBackground(guideBg);
        int guideSize = Math.min(dp(300), getResources().getDisplayMetrics().widthPixels - dp(70));
        FrameLayout.LayoutParams guideLp = new FrameLayout.LayoutParams(guideSize, guideSize);
        guideLp.gravity = Gravity.CENTER;
        root.addView(guide, guideLp);

        TextView help = new TextView(this);
        help.setText("QR-Code in den Rahmen halten");
        help.setTextColor(Color.WHITE);
        help.setTextSize(16);
        help.setGravity(Gravity.CENTER);
        help.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable helpBg = new GradientDrawable();
        helpBg.setColor(0x66000000);
        helpBg.setCornerRadius(dp(18));
        help.setBackground(helpBg);
        FrameLayout.LayoutParams helpLp = new FrameLayout.LayoutParams(dp(290), dp(52));
        helpLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        helpLp.bottomMargin = dp(96);
        root.addView(help, helpLp);

        TextView cancel = new TextView(this);
        cancel.setText("Abbrechen");
        cancel.setTextColor(Color.WHITE);
        cancel.setTextSize(16);
        cancel.setGravity(Gravity.CENTER);
        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setColor(0x99000000);
        cancelBg.setCornerRadius(dp(24));
        cancel.setBackground(cancelBg);
        cancel.setOnClickListener(v -> cancelScanner());
        FrameLayout.LayoutParams cancelLp = new FrameLayout.LayoutParams(dp(150), dp(50));
        cancelLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        cancelLp.bottomMargin = dp(28);
        root.addView(cancel, cancelLp);
    }

    private void startCamera() {
        try {
            final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
            future.addListener(() -> {
                try {
                    cameraProvider = future.get();
                    runOnUiThread(this::bindCamera);
                } catch (Exception e) {
                    runOnUiThread(() -> showCameraError(e));
                }
            }, Runnable::run);
        } catch (Exception e) {
            showCameraError(e);
        }
    }

    private void bindCamera() {
        if (isFinishing() || cameraProvider == null) return;
        try {
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
            analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

            cameraProvider.unbindAll();
            CameraSelector selector = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                    ? CameraSelector.DEFAULT_BACK_CAMERA
                    : CameraSelector.DEFAULT_FRONT_CAMERA;
            cameraProvider.bindToLifecycle(this, selector, preview, analysis);
        } catch (Exception e) {
            showCameraError(e);
        }
    }

    private void analyzeImage(ImageProxy image) {
        if (finished.get()) {
            image.close();
            return;
        }

        try {
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();

            byte[] y = new byte[width * height];
            int originalPosition = buffer.position();

            for (int row = 0; row < height; row++) {
                int rowStart = originalPosition + row * rowStride;
                for (int col = 0; col < width; col++) {
                    int index = rowStart + col * pixelStride;
                    if (index < buffer.limit()) {
                        y[row * width + col] = buffer.get(index);
                    }
                }
            }

            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    y, width, height, 0, 0, width, height, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = reader.decodeWithState(bitmap);

            if (result != null && result.getText() != null && finished.compareAndSet(false, true)) {
                Intent data = new Intent();
                data.putExtra("SCAN_RESULT", result.getText());
                data.putExtra("SCAN_RESULT_FORMAT", result.getBarcodeFormat().toString());
                runOnUiThread(() -> {
                    setResult(RESULT_OK, data);
                    finish();
                });
            }
        } catch (com.google.zxing.NotFoundException ignored) {
            // Normal: no QR code in this frame.
        } catch (Exception e) {
            // Do not crash the kiosk because a single camera frame was malformed.
        } finally {
            try {
                reader.reset();
            } catch (Exception ignored) { }
            image.close();
        }
    }

    private void showCameraError(Exception error) {
        if (isFinishing() || finished.get()) return;
        String detail = error == null ? "Unbekannter Kamerafehler" : error.getClass().getSimpleName();
        new AlertDialog.Builder(this)
                .setTitle("Kamera konnte nicht gestartet werden")
                .setMessage("Der QR-Scanner konnte die Kamera nicht öffnen.\n\nFehler: " + detail)
                .setCancelable(false)
                .setPositiveButton("Zurück", (d, w) -> cancelScanner())
                .show();
    }

    private void cancelScanner() {
        finished.set(true);
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Kamerazugriff erforderlich")
                        .setMessage("Zum Scannen des Anamnese-QR-Codes benötigt Patient Kiosk Zugriff auf die Kamera.")
                        .setCancelable(false)
                        .setPositiveButton("Zurück", (d, w) -> cancelScanner())
                        .show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideUi();
    }

    @Override
    protected void onDestroy() {
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception ignored) { }
        }
        if (cameraExecutor != null) cameraExecutor.shutdownNow();
        super.onDestroy();
    }

    private void hideUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
