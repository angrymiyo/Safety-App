package com.example.safetyapp;

import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.FirebaseNetworkException;

public class ResetPasswordActivity extends AppCompatActivity {

    private static final String TAG = "ResetPasswordActivity";

    private EditText etEmail;
    private ProgressBar progressBar;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reset_password);

        // Apply gradient to title text
        android.widget.TextView titleText = findViewById(R.id.titleText);
        TextPaint paint = titleText.getPaint();
        float width = paint.measureText(titleText.getText().toString());

        Shader textShader = new LinearGradient(0, 0, width, titleText.getTextSize(),
                new int[]{
                        0xFF667eea,
                        0xFF764ba2,
                        0xFFf093fb
                }, null, Shader.TileMode.CLAMP);
        titleText.getPaint().setShader(textShader);

        // Initialize Firebase Auth
        mAuth = FirebaseAuth.getInstance();

        // Initialize views
        etEmail = findViewById(R.id.inputEmail);
        progressBar = findViewById(R.id.progress_bar);

        // Back button
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // Send reset link button
        findViewById(R.id.btnSendResetLink).setOnClickListener(v -> sendResetEmail());

        // Back to login button
        findViewById(R.id.btnBackToLogin).setOnClickListener(v -> finish());
    }

    private void sendResetEmail() {
        String email = etEmail.getText().toString().trim();

        // Validate email
        if (TextUtils.isEmpty(email)) {
            etEmail.setError("Email is required");
            etEmail.requestFocus();
            return;
        }

        // Check if email is valid format
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.setError("Please enter a valid email address");
            etEmail.requestFocus();
            return;
        }

        // Show progress
        progressBar.setVisibility(View.VISIBLE);
        findViewById(R.id.btnSendResetLink).setEnabled(false);

        Log.d(TAG, "===== PASSWORD RESET PROCESS STARTED =====");
        Log.d(TAG, "Email entered: " + email);

        // Send password reset email
        mAuth.sendPasswordResetEmail(email)
                .addOnSuccessListener(aVoid -> {
                    progressBar.setVisibility(View.GONE);
                    findViewById(R.id.btnSendResetLink).setEnabled(true);
                    Log.d(TAG, "✓ Password reset email sent successfully!");
                    Log.d(TAG, "===== PASSWORD RESET PROCESS COMPLETED =====");

                    showSuccessDialog(email);
                })
                .addOnFailureListener(e -> {
                    progressBar.setVisibility(View.GONE);
                    findViewById(R.id.btnSendResetLink).setEnabled(true);
                    Log.e(TAG, "✗ Failed to send reset email", e);
                    Log.e(TAG, "Error type: " + e.getClass().getSimpleName());
                    Log.e(TAG, "Error message: " + e.getMessage());
                    Log.e(TAG, "===== PASSWORD RESET PROCESS FAILED =====");

                    String errorMessage = "Failed to send reset email.";
                    String troubleshooting = "";

                    if (e instanceof FirebaseAuthInvalidUserException) {
                        errorMessage = "Unable to send reset email.";
                        troubleshooting = "\n\nPossible reasons:\n" +
                                "• Account may not exist with this email\n" +
                                "• Account may use Google Sign-In only\n" +
                                "• Email spelling might be incorrect\n\n" +
                                "Try:\n" +
                                "• Sign in with Google if you used that\n" +
                                "• Verify email address is correct\n" +
                                "• Create new account if needed";
                    } else if (e instanceof FirebaseAuthInvalidCredentialsException) {
                        errorMessage = "Invalid email format.";
                        troubleshooting = "\n\nPlease enter a valid email address.";
                    } else if (e instanceof FirebaseNetworkException) {
                        errorMessage = "Network error occurred.";
                        troubleshooting = "\n\nPlease:\n• Check internet connection\n• Try again";
                    } else if (e.getMessage() != null) {
                        errorMessage = e.getMessage();
                    }

                    showErrorDialog("Error Sending Email", errorMessage + troubleshooting);
                });
    }

    private void showSuccessDialog(String email) {
        new AlertDialog.Builder(this)
                .setTitle("✓ Reset Email Sent")
                .setMessage("If an account exists with:\n" +
                        email +
                        "\n\nYou will receive a password reset link.\n\n" +
                        "IMPORTANT:\n" +
                        "• Check your inbox (1-5 minutes)\n" +
                        "• Check spam/junk folder\n" +
                        "• Look for email from Firebase\n" +
                        "• Link expires in 1 hour\n\n" +
                        "NOTE: Password reset only works for email/password accounts. " +
                        "If you signed up with Google, please use Google Sign-In.")
                .setPositiveButton("OK", (dialog, which) -> {
                    // Clear email field
                    etEmail.setText("");
                    // Optionally go back to login
                    finish();
                })
                .setNeutralButton("Troubleshoot", (dialog, which) -> showTroubleshootingGuide())
                .setCancelable(false)
                .show();
    }

    private void showErrorDialog(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void showTroubleshootingGuide() {
        new AlertDialog.Builder(this)
                .setTitle("Troubleshooting Guide")
                .setMessage("If you're not receiving the reset email:\n\n" +
                        "1. WAIT 5-10 MINUTES\n" +
                        "   Email delivery can be delayed\n\n" +
                        "2. CHECK SPAM/JUNK FOLDER\n" +
                        "   Look for emails from:\n" +
                        "   • noreply@firebase.com\n" +
                        "   • Your app name\n\n" +
                        "3. EMAIL PROVIDER ISSUES\n" +
                        "   Some providers block Firebase:\n" +
                        "   • Try different email (Gmail works best)\n" +
                        "   • Check provider settings\n\n" +
                        "4. VERIFY EMAIL ADDRESS\n" +
                        "   • Must match registration email\n" +
                        "   • Check for typos\n\n" +
                        "5. GOOGLE SIGN-IN ACCOUNTS\n" +
                        "   • Password reset doesn't work for Google accounts\n" +
                        "   • Use 'Continue with Google' button instead\n\n" +
                        "6. FIREWALL/SECURITY\n" +
                        "   • Corporate/school email may block\n" +
                        "   • Add Firebase to whitelist\n\n" +
                        "7. TRY AGAIN LATER\n" +
                        "   Firebase may have temporary delays")
                .setPositiveButton("OK", null)
                .show();
    }
}
