package com.example.safetyapp;

import android.os.Bundle;
import android.widget.*;

import com.example.safetyapp.helper.EmergencyMessageHelper;

public class SafeZoneActivity extends BaseActivity {

    private EmergencyMessageHelper helper;
    private RadioGroup radioGroupMethod;
    private Button btnSendOk;
    private EditText etCustomMessage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_safe_zone);

        helper = new EmergencyMessageHelper(this);

        radioGroupMethod = findViewById(R.id.radio_group_method);
        btnSendOk = findViewById(R.id.btn_send_ok);
        etCustomMessage = findViewById(R.id.et_custom_message);

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
