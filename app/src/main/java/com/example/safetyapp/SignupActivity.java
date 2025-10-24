package com.example.safetyapp;

import android.content.Intent;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.*;
import com.google.firebase.firestore.*;

import java.util.HashMap;
import java.util.Map;

public class SignupActivity extends AppCompatActivity {

    private EditText etEmail, etPassword, etConfirmPassword;
    private ProgressBar progressBar;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private boolean isPasswordVisible = false;
    private boolean isConfirmPasswordVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_signup);

        // Apply gradient to welcome text
        TextView welcomeText = findViewById(R.id.welcomeText);
        TextPaint paint = welcomeText.getPaint();
        float width = paint.measureText(welcomeText.getText().toString());

        Shader textShader = new LinearGradient(0, 0, width, welcomeText.getTextSize(),
                new int[]{
                    0xFF667eea,
                    0xFF764ba2,
                    0xFFf093fb
                }, null, Shader.TileMode.CLAMP);
        welcomeText.getPaint().setShader(textShader);

        db = FirebaseFirestore.getInstance();
        mAuth = FirebaseAuth.getInstance();

        etEmail = findViewById(R.id.et_email);
        etPassword = findViewById(R.id.et_password);
        etConfirmPassword = findViewById(R.id.inputConfirmPassword);
        progressBar = findViewById(R.id.progress_bar);

        // Password visibility toggle
        ImageView togglePasswordVisibility = findViewById(R.id.togglePasswordVisibility);
        togglePasswordVisibility.setOnClickListener(v -> {
            if (isPasswordVisible) {
                // Hide password
                etPassword.setTransformationMethod(PasswordTransformationMethod.getInstance());
                togglePasswordVisibility.setImageResource(R.drawable.ic_eye_closed);
                isPasswordVisible = false;
            } else {
                // Show password
                etPassword.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
                togglePasswordVisibility.setImageResource(R.drawable.ic_eye_open);
                isPasswordVisible = true;
            }
            // Move cursor to end of text
            etPassword.setSelection(etPassword.getText().length());
        });

        // Confirm Password visibility toggle
        ImageView toggleConfirmPasswordVisibility = findViewById(R.id.toggleConfirmPasswordVisibility);
        toggleConfirmPasswordVisibility.setOnClickListener(v -> {
            if (isConfirmPasswordVisible) {
                // Hide password
                etConfirmPassword.setTransformationMethod(PasswordTransformationMethod.getInstance());
                toggleConfirmPasswordVisibility.setImageResource(R.drawable.ic_eye_closed);
                isConfirmPasswordVisible = false;
            } else {
                // Show password
                etConfirmPassword.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
                toggleConfirmPasswordVisibility.setImageResource(R.drawable.ic_eye_open);
                isConfirmPasswordVisible = true;
            }
            // Move cursor to end of text
            etConfirmPassword.setSelection(etConfirmPassword.getText().length());
        });

        findViewById(R.id.btn_signup).setOnClickListener(v -> signupUser());
        findViewById(R.id.btn_go_to_login).setOnClickListener(v ->
                startActivity(new Intent(this, LoginActivity.class)));
    }

    private void signupUser() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        String confirmPassword = etConfirmPassword.getText().toString().trim();

        // Input Validation
        if (TextUtils.isEmpty(email)) {
            etEmail.setError("Email is required");
            etEmail.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(password)) {
            etPassword.setError("Password is required");
            etPassword.requestFocus();
            return;
        }
        if (password.length() < 6) {
            etPassword.setError("Password must be at least 6 characters");
            etPassword.requestFocus();
            return;
        }
        if (!password.equals(confirmPassword)) {
            etConfirmPassword.setError("Passwords do not match");
            etConfirmPassword.requestFocus();
            return;
        }

        // Firebase Registration
        progressBar.setVisibility(View.VISIBLE);
        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    progressBar.setVisibility(View.GONE);
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            saveUserDataToFirestore(user.getUid());
                        }
                    } else {
                        Toast.makeText(this, "Sign up failed: " +
                                        (task.getException() != null ? task.getException().getMessage() : "Unknown error"),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void saveUserDataToFirestore(String userId) {
        Map<String, Object> user = new HashMap<>();
        user.put("emergencyMessage", "HELP!!!");

        db.collection("users").document(userId)
                .set(user, SetOptions.merge())
                .addOnSuccessListener(unused -> {
                    Toast.makeText(this, "Account created successfully", Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(SignupActivity.this, MainActivity.class));
                    finish();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to save user data: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }
}
