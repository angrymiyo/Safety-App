package com.example.safetyapp;

import static android.Manifest.permission.VIBRATE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class EmergencyActivity extends AppCompatActivity {
    private static final int COUNTDOWN_SECONDS = 30;
    private TextView countdownText;
    private Vibrator vibrator;


    private void triggerEmergencyActions() {
        // Temporary empty implementation
        Log.d("Emergency", "Emergency actions placeholder");
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emergency);

        // Keep screen awake
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        countdownText = findViewById(R.id.countdown_text);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        startCountdown();
    }

    private void startCountdown() {
        new CountDownTimer(COUNTDOWN_SECONDS * 1000, 1000) {
            public void onTick(long millisUntilFinished) {
                updateUI(millisUntilFinished / 1000);
            }

            public void onFinish() {
                triggerEmergencyActions();
                finish();
            }
        }.start();
    }

    private void updateUI(long secondsLeft) {
        countdownText.setText(getString(R.string.countdown_message, secondsLeft));
        vibrate(500); // Short vibration feedback
    }

    public void onCancelClicked(View view) {
        finish(); // cancel emergency
    }

    private void vibrate(long duration) {
        if (vibrator.hasVibrator()) {
            // Check permission for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(VIBRATE) != PERMISSION_GRANTED) {
                    Log.w("Vibration", "VIBRATE permission not granted");
                    return;
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(duration);
            }
        }
    }
}

