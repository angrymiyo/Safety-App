package com.example.safetyapp;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

public class InCaseEmergencyActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Setup layout with toolbar, back button, and no bottom nav
        setupLayout(R.layout.activity_in_case_emergency, "Emergency Services", true, R.id.nav_home, false);

        // Setup all emergency services
        setupService(R.id.service_police, "Police", "999", R.drawable.e911_emergency_24px);
        setupService(R.id.service_ambulance, "Ambulance", "103", R.drawable.ambulance_24px);
        setupService(R.id.service_fire, "Fire Service", "102", R.drawable.fire_truck_24px);
        setupService(R.id.service_hospital, "Local Hospital", "16263", R.drawable.ambulance_24px);
        setupService(R.id.service_child, "Child Helpline", "1098", R.drawable.account_child_24px);
        setupService(R.id.service_women, "Violence Against Women", "109", R.drawable.account_child_24px);
    }

    private void setupService(int serviceId, String name, String phone, int iconRes) {
        View serviceView = findViewById(serviceId);

        ImageView icon = serviceView.findViewById(R.id.iv_service_icon);
        TextView nameText = serviceView.findViewById(R.id.tv_service_name);
        TextView phoneText = serviceView.findViewById(R.id.tv_service_phone);
        Button callButton = serviceView.findViewById(R.id.btn_call_service);

        icon.setImageResource(iconRes);
        nameText.setText(name);
        phoneText.setText(phone);

        View.OnClickListener callListener = v -> {
            Intent callIntent = new Intent(Intent.ACTION_DIAL);
            callIntent.setData(Uri.parse("tel:" + phone));
            startActivity(callIntent);
        };

        callButton.setOnClickListener(callListener);
        serviceView.setOnClickListener(callListener);
    }
}
