package com.example.safetyapp;

import android.os.Bundle;
import android.widget.*;

import androidx.cardview.widget.CardView;

import com.example.safetyapp.helper.EmergencyMessageHelper;

public class SafeZoneActivity extends BaseActivity {

    private EmergencyMessageHelper helper;
    private RadioGroup radioGroupMethod;
    private RadioButton radioSms, radioWhatsApp;
    private Button btnSendOk;
    private EditText etCustomMessage;
    private CardView cardSms, cardWhatsApp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Setup layout with toolbar, back button, and no bottom nav
        setupLayout(R.layout.activity_safe_zone, "I'm Safe", true, R.id.nav_home, false);

        helper = new EmergencyMessageHelper(this);

        radioGroupMethod = findViewById(R.id.radio_group_method);
        radioSms = findViewById(R.id.radio_sms);
        radioWhatsApp = findViewById(R.id.radio_whatsapp);
        btnSendOk = findViewById(R.id.btn_send_ok);
        etCustomMessage = findViewById(R.id.et_custom_message);
        cardSms = findViewById(R.id.card_sms);
        cardWhatsApp = findViewById(R.id.card_whatsapp);

        // Set up card click listeners to toggle radio buttons
        cardSms.setOnClickListener(v -> radioSms.setChecked(true));
        cardWhatsApp.setOnClickListener(v -> radioWhatsApp.setChecked(true));

        btnSendOk.setOnClickListener(v -> {
            int selectedId = radioGroupMethod.getCheckedRadioButtonId();

            if (selectedId == -1) {
                Toast.makeText(this, "Please select a sending method", Toast.LENGTH_SHORT).show();
                return;
            }

            String method = "";
            if (selectedId == R.id.radio_sms) {
                method = "sms";
            } else if (selectedId == R.id.radio_whatsapp) {
                method = "whatsapp";
            }

            String customMessage = etCustomMessage.getText().toString().trim();

            String messageToSend = customMessage.isEmpty()
                    ? "Hi, I'm safe now. Just wanted to let you know!"
                    : customMessage;

            helper.sendCustomMessage(method, messageToSend);
        });
    }
}
