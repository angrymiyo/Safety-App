package com.example.safetyapp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.database.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;

public class ProfileActivity extends BaseActivity {

    private TextView usernameTextView, emailTextView;
    private TextView textName, textMobile, textEmail, textAddress;
    private EditText editName, editMobile, editEmail, editAddress;
    private MaterialButton editProfileButton;
    private ProgressBar progressBar;
    private ImageView profileImageView;

    private DatabaseReference userRef;
    private FirebaseUser currentUser;
    private boolean isEditing = false;
    private ActivityResultLauncher<Intent> imagePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupLayout(R.layout.activity_profile, "Profile", true, R.id.nav_profile, false);

        // Initialize views
        usernameTextView = findViewById(R.id.username);
        emailTextView = findViewById(R.id.email);
        textName = findViewById(R.id.text_name);
        textMobile = findViewById(R.id.text_mobile);
        textEmail = findViewById(R.id.text_email);
        textAddress = findViewById(R.id.text_address);

        editName = findViewById(R.id.edit_name);
        editMobile = findViewById(R.id.edit_mobile);
        editEmail = findViewById(R.id.edit_email);
        editAddress = findViewById(R.id.edit_address);

        editProfileButton = findViewById(R.id.btn_edit_profile);
        progressBar = findViewById(R.id.progressBar);
        profileImageView = findViewById(R.id.profile_image);

        currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            Toast.makeText(this, "User not logged in!", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        userRef = FirebaseDatabase.getInstance().getReference("Users").child(currentUser.getUid());

        // Initialize image picker launcher
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri imageUri = result.getData().getData();
                        if (imageUri != null) {
                            saveImageToDatabase(imageUri);
                        }
                    }
                }
        );

        // Set click listener on profile image
        profileImageView.setOnClickListener(v -> openImagePicker());

        loadProfile();

        editProfileButton.setOnClickListener(v -> {
            if (isEditing) {
                saveProfile();
            } else {
                enableEditing();
            }
        });

    }

    private void loadProfile() {
        progressBar.setVisibility(View.VISIBLE);
        userRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                progressBar.setVisibility(View.GONE);
                if (snapshot.exists()) {
                    String name = snapshot.child("name").getValue(String.class);
                    String mobile = snapshot.child("mobile").getValue(String.class);
                    String email = snapshot.child("email").getValue(String.class);
                    String address = snapshot.child("address").getValue(String.class);
                    String photoBase64 = snapshot.child("photoBase64").getValue(String.class);

                    textName.setText("Name: " + (name != null ? name : ""));
                    textMobile.setText("Mobile: " + (mobile != null ? mobile : ""));
                    textEmail.setText("Email: " + (email != null ? email : ""));
                    textAddress.setText("Address: " + (address != null ? address : ""));

                    usernameTextView.setText(name != null ? name : "Username");
                    emailTextView.setText(email != null ? email : "Email");

                    // Load profile image if available
                    if (photoBase64 != null && !photoBase64.isEmpty()) {
                        try {
                            byte[] decodedBytes = Base64.decode(photoBase64, Base64.DEFAULT);
                            Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                            profileImageView.setImageBitmap(bitmap);
                        } catch (Exception e) {
                            profileImageView.setImageResource(R.drawable.user_profile);
                        }
                    } else {
                        profileImageView.setImageResource(R.drawable.user_profile);
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(ProfileActivity.this, "Failed to load profile.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void enableEditing() {
        isEditing = true;
        editProfileButton.setText("Save");
        editProfileButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_save, 0, 0, 0);
        editProfileButton.setCompoundDrawablePadding(16);

        editName.setVisibility(View.VISIBLE);
        editMobile.setVisibility(View.VISIBLE);
        editEmail.setVisibility(View.VISIBLE);
        editAddress.setVisibility(View.VISIBLE);

        editName.setText(textName.getText().toString().replace("Name: ", ""));
        editMobile.setText(textMobile.getText().toString().replace("Mobile: ", ""));
        editEmail.setText(textEmail.getText().toString().replace("Email: ", ""));
        editAddress.setText(textAddress.getText().toString().replace("Address: ", ""));
    }

    private void saveProfile() {
        String name = editName.getText().toString().trim();
        String mobile = editMobile.getText().toString().trim();
        String email = editEmail.getText().toString().trim();
        String address = editAddress.getText().toString().trim();

        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(mobile) || TextUtils.isEmpty(email) || TextUtils.isEmpty(address)) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);

        HashMap<String, Object> profileMap = new HashMap<>();
        profileMap.put("name", name);
        profileMap.put("mobile", mobile);
        profileMap.put("email", email);
        profileMap.put("address", address);

        userRef.setValue(profileMap).addOnCompleteListener(task -> {
            progressBar.setVisibility(View.GONE);
            if (task.isSuccessful()) {
                Toast.makeText(ProfileActivity.this, "Profile updated", Toast.LENGTH_SHORT).show();
                isEditing = false;

                editProfileButton.setText("Edit Profile");
                editProfileButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.edit_24, 0, 0, 0);
                editProfileButton.setCompoundDrawablePadding(16);

                editName.setVisibility(View.GONE);
                editMobile.setVisibility(View.GONE);
                editEmail.setVisibility(View.GONE);
                editAddress.setVisibility(View.GONE);

                loadProfile();
            } else {
                Toast.makeText(ProfileActivity.this, "Failed to update profile", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        imagePickerLauncher.launch(intent);
    }

    private void saveImageToDatabase(Uri imageUri) {
        if (currentUser == null) {
            Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);

        try {
            // Read the image from URI
            InputStream inputStream = getContentResolver().openInputStream(imageUri);
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);

            // Compress and resize the image to reduce size
            Bitmap resizedBitmap = resizeBitmap(bitmap, 400, 400);

            // Convert to Base64
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream);
            byte[] imageBytes = outputStream.toByteArray();
            String base64Image = Base64.encodeToString(imageBytes, Base64.DEFAULT);

            // Save to Firebase Realtime Database
            userRef.child("photoBase64").setValue(base64Image)
                .addOnSuccessListener(aVoid -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(ProfileActivity.this, "Profile picture updated successfully", Toast.LENGTH_SHORT).show();

                    // Display the image
                    profileImageView.setImageBitmap(resizedBitmap);
                })
                .addOnFailureListener(e -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(ProfileActivity.this, "Failed to update profile picture: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });

            inputStream.close();
            outputStream.close();

        } catch (Exception e) {
            progressBar.setVisibility(View.GONE);
            Toast.makeText(this, "Failed to process image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap resizeBitmap(Bitmap bitmap, int maxWidth, int maxHeight) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        float scaleWidth = ((float) maxWidth) / width;
        float scaleHeight = ((float) maxHeight) / height;
        float scale = Math.min(scaleWidth, scaleHeight);

        int newWidth = Math.round(width * scale);
        int newHeight = Math.round(height * scale);

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
    }
}
