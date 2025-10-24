package com.example.safetyapp;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.*;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.*;
import com.google.firebase.FirebaseNetworkException;

public class LoginActivity extends AppCompatActivity {

    private static final int RC_SIGN_IN = 1001;
    private static final String TAG = "LoginActivity";

    private EditText etEmail, etPassword;
    private ProgressBar progressBar;
    private FirebaseAuth mAuth;
    private GoogleSignInClient mGoogleSignInClient;
    private boolean isPasswordVisible = false;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_login);

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

        // Firebase Auth
        mAuth = FirebaseAuth.getInstance();

        // UI
        etEmail = findViewById(R.id.inputEmail);
        etPassword = findViewById(R.id.inputPassword);
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

        // Google Sign-In
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        // Buttons
        findViewById(R.id.btnLogin).setOnClickListener(v -> attemptLogin());
        findViewById(R.id.btnNewAccount).setOnClickListener(v -> startActivity(new Intent(this, SignupActivity.class)));
        findViewById(R.id.googleSignInBtn).setOnClickListener(v -> signInWithGoogle());
        findViewById(R.id.forgetPassword).setOnClickListener(v -> showForgotPasswordDialog());
    }

    private void attemptLogin() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

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

        progressBar.setVisibility(View.VISIBLE);
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    progressBar.setVisibility(View.GONE);
                    if (task.isSuccessful()) {
                        Toast.makeText(this, "Login successful!", Toast.LENGTH_SHORT).show();
                        navigateToMain();
                    } else {
                        Exception e = task.getException();
                        Toast.makeText(this, "Login failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        e.printStackTrace();
                    }
                });
    }

    private void signInWithGoogle() {
        Intent signInIntent = mGoogleSignInClient.getSignInIntent();
        startActivityForResult(signInIntent, RC_SIGN_IN);
    }

    private void firebaseAuthWithGoogle(GoogleSignInAccount account) {
        AuthCredential credential = GoogleAuthProvider.getCredential(account.getIdToken(), null);
        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        Toast.makeText(this, "Signed in as: " + mAuth.getCurrentUser().getEmail(), Toast.LENGTH_SHORT).show();
                        navigateToMain();
                    } else {
                        Exception e = task.getException();
                        Toast.makeText(this, "Google authentication failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        e.printStackTrace();
                    }
                });
    }

    private void showForgotPasswordDialog() {
        EditText emailInput = new EditText(this);
        emailInput.setHint("Enter your registered email");
        emailInput.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailInput.setPadding(20, 20, 20, 20);

        new AlertDialog.Builder(this)
                .setTitle("Reset Password")
                .setMessage("We'll send a reset link to your email. Please check your inbox and spam folder.")
                .setView(emailInput)
                .setPositiveButton("Send", (dialog, which) -> {
                    String email = emailInput.getText().toString().trim();

                    // Validate email
                    if (TextUtils.isEmpty(email)) {
                        Toast.makeText(this, "Please enter your email.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // Check if email is valid format
                    if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                        Toast.makeText(this, "Please enter a valid email address.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    sendResetEmail(email);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendResetEmail(String email) {
        // Show progress
        progressBar.setVisibility(View.VISIBLE);

        Log.d(TAG, "===== PASSWORD RESET PROCESS STARTED =====");
        Log.d(TAG, "Email entered: " + email);
        Log.d(TAG, "Firebase Auth instance: " + (mAuth != null ? "Initialized" : "NULL"));

        // First, verify email format
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            progressBar.setVisibility(View.GONE);
            showErrorDialog("Invalid Email", "Please enter a valid email address.");
            return;
        }

        // Use fetchSignInMethodsForEmail to check if user exists
        mAuth.fetchSignInMethodsForEmail(email)
                .addOnCompleteListener(fetchTask -> {
                    if (fetchTask.isSuccessful()) {
                        SignInMethodQueryResult result = fetchTask.getResult();

                        if (result != null && result.getSignInMethods() != null && !result.getSignInMethods().isEmpty()) {
                            Log.d(TAG, "User exists with sign-in methods: " + result.getSignInMethods());
                            // User exists, proceed to send reset email
                            actualSendResetEmail(email);
                        } else {
                            progressBar.setVisibility(View.GONE);
                            Log.w(TAG, "No user found with email: " + email);
                            showErrorDialog("Account Not Found",
                                "No account exists with this email address.\n\n" +
                                "Please:\n" +
                                "• Check if email is correct\n" +
                                "• Create an account if you don't have one\n" +
                                "• Try the email you used during registration");
                        }
                    } else {
                        progressBar.setVisibility(View.GONE);
                        Exception e = fetchTask.getException();
                        Log.e(TAG, "Error checking user existence", e);
                        showErrorDialog("Connection Error",
                            "Failed to verify email.\n\n" +
                            "Error: " + (e != null ? e.getMessage() : "Unknown") +
                            "\n\nPlease check your internet connection.");
                    }
                });
    }

    private void actualSendResetEmail(String email) {
        Log.d(TAG, "Sending password reset email...");

        mAuth.sendPasswordResetEmail(email)
                .addOnSuccessListener(aVoid -> {
                    progressBar.setVisibility(View.GONE);
                    Log.d(TAG, "✓ Password reset email sent successfully!");
                    Log.d(TAG, "===== PASSWORD RESET PROCESS COMPLETED =====");

                    new AlertDialog.Builder(this)
                            .setTitle("✓ Email Sent Successfully")
                            .setMessage("A password reset link has been sent to:\n\n" +
                                       email +
                                       "\n\nIMPORTANT:\n" +
                                       "• Check your inbox (may take 1-5 minutes)\n" +
                                       "• Check spam/junk folder\n" +
                                       "• Look for email from Firebase\n" +
                                       "• Link expires in 1 hour\n\n" +
                                       "Still not receiving?\n" +
                                       "• Wait a few minutes\n" +
                                       "• Try again later\n" +
                                       "• Check email spelling")
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Troubleshoot", (dialog, which) -> showTroubleshootingGuide())
                            .show();
                })
                .addOnFailureListener(e -> {
                    progressBar.setVisibility(View.GONE);
                    Log.e(TAG, "✗ Failed to send reset email", e);
                    Log.e(TAG, "Error type: " + e.getClass().getSimpleName());
                    Log.e(TAG, "Error message: " + e.getMessage());
                    Log.e(TAG, "===== PASSWORD RESET PROCESS FAILED =====");

                    String errorMessage = "Failed to send reset email.";
                    String troubleshooting = "";

                    if (e instanceof FirebaseAuthInvalidUserException) {
                        errorMessage = "No account found with this email.";
                        troubleshooting = "\n\nPlease:\n• Verify email spelling\n• Create an account if needed";
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
                           "5. FIREWALL/SECURITY\n" +
                           "   • Corporate/school email may block\n" +
                           "   • Add Firebase to whitelist\n\n" +
                           "6. TRY AGAIN LATER\n" +
                           "   Firebase may have temporary delays")
                .setPositiveButton("OK", null)
                .show();
    }

    private void navigateToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Google sign-in
        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                GoogleSignInAccount account = task.getResult(ApiException.class);
                firebaseAuthWithGoogle(account);
            } catch (ApiException e) {
                Toast.makeText(this, "Google Sign-in failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mAuth.getCurrentUser() != null) {
            navigateToMain();
        }
    }
}
