package com.example.safetyapp;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;

public abstract class BaseActivity extends AppCompatActivity {

    protected DrawerLayout drawerLayout;
    protected FirebaseAuth mAuth;

    private ImageButton btnBack;
    private TextView tvToolbarTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAuth = FirebaseAuth.getInstance();
    }

    /**
     * Sets up the base layout with navigation drawer, bottom nav, toolbar with title and back button.
     *
     * @param layoutResId       The layout resource id for the activity's main content.
     * @param title             The toolbar title text. Pass null or empty string for no title.
     * @param showBackButton    True to show back button, false to hide.
     * @param selectedNavItemId The menu item id to select in BottomNavigationView.
     */
    protected void setupLayout(int layoutResId, @Nullable String title, boolean showBackButton, int selectedNavItemId) {
        setupLayout(layoutResId, title, showBackButton, selectedNavItemId, true);
    }

    /**
     * Sets up the base layout with navigation drawer, bottom nav, toolbar with title and back button.
     *
     * @param layoutResId       The layout resource id for the activity's main content.
     * @param title             The toolbar title text. Pass null or empty string for no title.
     * @param showBackButton    True to show back button, false to hide.
     * @param selectedNavItemId The menu item id to select in BottomNavigationView.
     * @param showBottomNav     True to show bottom navigation, false to hide.
     */
    protected void setupLayout(int layoutResId, @Nullable String title, boolean showBackButton, int selectedNavItemId, boolean showBottomNav) {
        setContentView(R.layout.activity_base);

        // Inflate the activity content layout into FrameLayout inside base layout
        FrameLayout contentFrame = findViewById(R.id.content_frame);
        getLayoutInflater().inflate(layoutResId, contentFrame, true);

        drawerLayout = findViewById(R.id.drawer_layout);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Find back button and title text inside toolbar
        btnBack = toolbar.findViewById(R.id.btn_back);
        tvToolbarTitle = toolbar.findViewById(R.id.tv_toolbar_title);

        if (getSupportActionBar() != null) {
            // Disable default title since we use custom TextView for title
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        // Set toolbar title
        if (tvToolbarTitle != null) {
            tvToolbarTitle.setText(title != null ? title : "");
        }

        // Show or hide back button and setup its click listener
        if (btnBack != null) {
            btnBack.setVisibility(showBackButton ? View.VISIBLE : View.GONE);
            btnBack.setOnClickListener(v -> onBackPressed());
        }

        // Setup bottom navigation listener and set selected item
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);

        // Show or hide bottom navigation
        if (bottomNav != null) {
            bottomNav.setVisibility(showBottomNav ? View.VISIBLE : View.GONE);
        }

        if (showBottomNav) {
            // Select the current item
            bottomNav.setSelectedItemId(selectedNavItemId);

            bottomNav.setOnItemSelectedListener(item -> {
                int id = item.getItemId();

                if (id == R.id.nav_home) {
                    if (selectedNavItemId != R.id.nav_home) {
                        Intent intent = new Intent(this, MainActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        finish();
                    }
                    return true;
                } else if (id == R.id.nav_profile) {
                    if (selectedNavItemId != R.id.nav_profile) {
                        startActivity(new Intent(this, ProfileActivity.class));
                        finish();
                    }
                    return true;
                }
                return false;
            });
        }
    }


    @Override
    protected void onStart() {
        super.onStart();

        // Redirect to login if user not logged in
        if (mAuth.getCurrentUser() == null) {
            redirectToLogin();
        }
    }

    private void logoutUser() {
        mAuth.signOut();
        redirectToLogin();
    }

    private void redirectToLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}
