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
import com.canhub.cropper.CropImageContract;
import com.canhub.cropper.CropImageContractOptions;
import com.canhub.cropper.CropImageOptions;
import com.canhub.cropper.CropImageView;
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
    private ImageView profileImageView, coverPhotoView;
    private com.google.android.material.floatingactionbutton.FloatingActionButton btnEditCover, btnEditProfilePic;

    private DatabaseReference userRef;
    private FirebaseUser currentUser;
    private boolean isEditing = false;
    private boolean isSelectingCoverPhoto = false;
    private ActivityResultLauncher<CropImageContractOptions> cropImageLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupLayout(R.layout.activity_profile, "Profile", true, R.id.nav_home, false);

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
        coverPhotoView = findViewById(R.id.cover_photo);
        btnEditCover = findViewById(R.id.btn_edit_cover);
        btnEditProfilePic = findViewById(R.id.btn_edit_profile_pic);

        currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            Toast.makeText(this, "User not logged in!", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        userRef = FirebaseDatabase.getInstance().getReference("Users").child(currentUser.getUid());

        // Initialize crop image launcher
        cropImageLauncher = registerForActivityResult(
                new CropImageContract(),
                result -> {
                    if (result.isSuccessful()) {
                        Uri croppedUri = result.getUriContent();
                        if (croppedUri != null) {
                            if (isSelectingCoverPhoto) {
                                saveCoverPhotoToDatabase(croppedUri);
                            } else {
                                saveProfilePictureToDatabase(croppedUri);
                            }
                        }
                    } else {
                        Exception error = result.getError();
                        if (error != null) {
                            Toast.makeText(this, "Cropping error: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );

        // Set click listeners for profile and cover photo
        profileImageView.setOnClickListener(v -> openProfilePicturePicker());
        btnEditProfilePic.setOnClickListener(v -> openProfilePicturePicker());
        btnEditCover.setOnClickListener(v -> openCoverPhotoPicker());

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
                    String coverPhotoBase64 = snapshot.child("coverPhotoBase64").getValue(String.class);

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

                    // Load cover photo if available
                    if (coverPhotoBase64 != null && !coverPhotoBase64.isEmpty()) {
                        try {
                            byte[] decodedBytes = Base64.decode(coverPhotoBase64, Base64.DEFAULT);
                            Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                            coverPhotoView.setImageBitmap(bitmap);
                        } catch (Exception e) {
                            // Keep default gradient background
                        }
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

        progressBar.setVisibility(View.VISIBLE);

        HashMap<String, Object> profileMap = new HashMap<>();

        // Only add fields that have values (making all fields optional)
        if (!TextUtils.isEmpty(name)) {
            profileMap.put("name", name);
        }
        if (!TextUtils.isEmpty(mobile)) {
            profileMap.put("mobile", mobile);
        }
        if (!TextUtils.isEmpty(email)) {
            profileMap.put("email", email);
        }
        if (!TextUtils.isEmpty(address)) {
            profileMap.put("address", address);
        }

        // Use updateChildren instead of setValue to only update provided fields
        userRef.updateChildren(profileMap).addOnCompleteListener(task -> {
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

    private void openProfilePicturePicker() {
        isSelectingCoverPhoto = false;

        CropImageOptions cropImageOptions = new CropImageOptions();
        cropImageOptions.imageSourceIncludeGallery = true;
        cropImageOptions.imageSourceIncludeCamera = true;
        cropImageOptions.aspectRatioX = 1;
        cropImageOptions.aspectRatioY = 1;
        cropImageOptions.cropShape = CropImageView.CropShape.OVAL;
        cropImageOptions.guidelines = CropImageView.Guidelines.ON;
        cropImageOptions.fixAspectRatio = true;
        cropImageOptions.outputCompressQuality = 80;
        cropImageOptions.activityTitle = "Crop Profile Picture";
        cropImageOptions.activityMenuIconColor = 0xFFFFFFFF;
        cropImageOptions.allowRotation = true;
        cropImageOptions.allowFlipping = true;
        cropImageOptions.showCropOverlay = true;
        cropImageOptions.showProgressBar = true;
        cropImageOptions.autoZoomEnabled = true;

        CropImageContractOptions cropImageContractOptions = new CropImageContractOptions(null, cropImageOptions);
        cropImageLauncher.launch(cropImageContractOptions);
    }

    private void openCoverPhotoPicker() {
        isSelectingCoverPhoto = true;

        CropImageOptions cropImageOptions = new CropImageOptions();
        cropImageOptions.imageSourceIncludeGallery = true;
        cropImageOptions.imageSourceIncludeCamera = true;
        cropImageOptions.aspectRatioX = 16;
        cropImageOptions.aspectRatioY = 9;
        cropImageOptions.cropShape = CropImageView.CropShape.RECTANGLE;
        cropImageOptions.guidelines = CropImageView.Guidelines.ON;
        cropImageOptions.fixAspectRatio = true;
        cropImageOptions.outputCompressQuality = 80;
        cropImageOptions.activityTitle = "Crop Cover Photo";
        cropImageOptions.activityMenuIconColor = 0xFFFFFFFF;
        cropImageOptions.allowRotation = true;
        cropImageOptions.allowFlipping = true;
        cropImageOptions.showCropOverlay = true;
        cropImageOptions.showProgressBar = true;
        cropImageOptions.autoZoomEnabled = true;

        CropImageContractOptions cropImageContractOptions = new CropImageContractOptions(null, cropImageOptions);
        cropImageLauncher.launch(cropImageContractOptions);
    }

    private void saveProfilePictureToDatabase(Uri imageUri) {
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

    private void saveCoverPhotoToDatabase(Uri imageUri) {
        if (currentUser == null) {
            Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);

        try {
            // Read the image from URI
            InputStream inputStream = getContentResolver().openInputStream(imageUri);
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);

            // Compress and resize the image (wider for cover photo)
            Bitmap resizedBitmap = resizeBitmap(bitmap, 800, 400);

            // Convert to Base64
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream);
            byte[] imageBytes = outputStream.toByteArray();
            String base64Image = Base64.encodeToString(imageBytes, Base64.DEFAULT);

            // Save to Firebase Realtime Database
            userRef.child("coverPhotoBase64").setValue(base64Image)
                .addOnSuccessListener(aVoid -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(ProfileActivity.this, "Cover photo updated successfully", Toast.LENGTH_SHORT).show();

                    // Display the image
                    coverPhotoView.setImageBitmap(resizedBitmap);
                    coverPhotoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                })
                .addOnFailureListener(e -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(ProfileActivity.this, "Failed to update cover photo: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
