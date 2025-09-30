package com.example.safetyapp;

import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Vibrator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.safetyapp.helper.EmergencyMessageHelper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

public class PopupCountdownActivity extends AppCompatActivity {

    private TextView tvCountdown, tvMessage;
    private Button btnCancel;
    private CountDownTimer countDownTimer;
    private int countdownSeconds = 0;
    private String method;
    private EmergencyMessageHelper messageHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show on lock screen (without dismissing keyguard)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        }

        setContentView(R.layout.activity_popup_countdown);

        tvCountdown = findViewById(R.id.tv_countdown);
        tvMessage = findViewById(R.id.tv_message);
        btnCancel = findViewById(R.id.btn_cancel);

        method = getIntent().getStringExtra("method");
        messageHelper = new EmergencyMessageHelper(this);

        fetchCountdownTimerFromFirebase();

        btnCancel.setOnClickListener(v -> {
            if (countDownTimer != null) countDownTimer.cancel();
            Toast.makeText(this, "Emergency message cancelled", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void fetchCountdownTimerFromFirebase() {
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("Users")
                .child(uid)
                .child("countdown_timer_seconds");

        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                countdownSeconds = snapshot.exists() ? snapshot.getValue(Integer.class) : 0;
                startCountdown(countdownSeconds);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Toast.makeText(PopupCountdownActivity.this, "Failed to fetch timer", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void startCountdown(int seconds) {
        tvMessage.setText("Sending emergency message in...");

        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        countDownTimer = new CountDownTimer(seconds * 1000L, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int sec = (int) (millisUntilFinished / 1000);
                tvCountdown.setText(sec + "s");

                // Bounce animation
                Animation anim = AnimationUtils.loadAnimation(PopupCountdownActivity.this, R.anim.scale_bounce);
                tvCountdown.startAnimation(anim);

                // 📳 Vibrate
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(100);
                }
            }

            @Override
            public void onFinish() {
                tvCountdown.setText("Sending...");
                messageHelper.sendMessage(method);
                finish();
            }
        };

        countDownTimer.start();
    }

    @Override
    protected void onDestroy() {
        if (countDownTimer != null) countDownTimer.cancel();
        super.onDestroy();
    }
}
