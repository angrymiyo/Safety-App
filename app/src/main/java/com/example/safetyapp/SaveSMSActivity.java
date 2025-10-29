package com.example.safetyapp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.View;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.safetyapp.adapter.ContactsAdapter;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SaveSMSActivity extends BaseActivity {

    private DatabaseReference dbRef;
    private FirebaseUser currentUser;
    private ContactsAdapter adapter;
    private List<Contact> contacts = new ArrayList<>();

    private View tvNoContacts;
    private RecyclerView rvContacts;
    private Button btnAddContact, btnSaveMessage;
    private EditText etEmergencyMessage;
    private Spinner spinnerCountdown;

    private static final int REQUEST_CONTACT_PERMISSION = 1001;
    private static final int REQUEST_PICK_CONTACT = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Setup layout with toolbar, back button, and no bottom nav
        setupLayout(R.layout.activity_save_sms, "Emergency Contacts", true, R.id.nav_home, false);

        currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        dbRef = FirebaseDatabase.getInstance().getReference("Users")
                .child(currentUser.getUid()).child("emergencyContacts");

        // Views
        btnAddContact = findViewById(R.id.btn_add_contact);
        btnSaveMessage = findViewById(R.id.btn_save_message);
        etEmergencyMessage = findViewById(R.id.et_emergency_message);
        spinnerCountdown = findViewById(R.id.spinner_countdown);
        tvNoContacts = findViewById(R.id.tv_no_contacts);
        rvContacts = findViewById(R.id.rv_contacts);

        rvContacts.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContactsAdapter(contacts, this::deleteContact, this::editContact);
        rvContacts.setAdapter(adapter);

        loadEmergencyContacts();
        loadSavedMessage();

        btnAddContact.setOnClickListener(this::showContactOptionsMenu);

        btnSaveMessage.setOnClickListener(v -> {
            String message = etEmergencyMessage.getText().toString().trim();
            if (message.isEmpty()) {
                Toast.makeText(this, "Message cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }

            int countdownSeconds;
            switch (spinnerCountdown.getSelectedItemPosition()) {
                case 1: countdownSeconds = 5; break;
                case 2: countdownSeconds = 10; break;
                case 3: countdownSeconds = 30; break;
                case 4: countdownSeconds = 0; break;
                default: countdownSeconds = 5; break;
            }

            DatabaseReference userRef = FirebaseDatabase.getInstance()
                    .getReference("Users")
                    .child(currentUser.getUid());

            Map<String, Object> updateMap = new HashMap<>();
            updateMap.put("emergency_message_template", message);
            updateMap.put("countdown_timer_seconds", countdownSeconds);

            userRef.updateChildren(updateMap)
                    .addOnSuccessListener(unused -> Toast.makeText(this, "Message & Countdown saved", Toast.LENGTH_SHORT).show())
                    .addOnFailureListener(e -> Toast.makeText(this, "Failed to save data", Toast.LENGTH_SHORT).show());
        });
    }

    private void loadSavedMessage() {
        DatabaseReference userRef = FirebaseDatabase.getInstance().getReference("Users").child(currentUser.getUid());
        userRef.child("emergency_message_template").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String savedMessage = snapshot.getValue(String.class);
                    etEmergencyMessage.setText(savedMessage);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(SaveSMSActivity.this, "Failed to load saved message", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadEmergencyContacts() {
        dbRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                contacts.clear();
                for (DataSnapshot contactSnapshot : snapshot.getChildren()) {
                    Contact contact = contactSnapshot.getValue(Contact.class);
                    if (contact != null) {
                        contact.setId(contactSnapshot.getKey());
                        contacts.add(contact);
                    }
                }
                adapter.notifyDataSetChanged();
                tvNoContacts.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(SaveSMSActivity.this, "Failed to load contacts", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showContactOptionsMenu(View anchor) {
        PopupMenu popupMenu = new PopupMenu(this, anchor);
        popupMenu.getMenuInflater().inflate(R.menu.contact_menu, popupMenu.getMenu());
        popupMenu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_add_manual) {
                showAddContactDialog();
                return true;
            } else if (item.getItemId() == R.id.menu_pick_contact) {
                checkContactPermission();
                return true;
            }
            return false;
        });
        popupMenu.show();
    }

    private void showAddContactDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_add_contact, null);
        EditText etName = view.findViewById(R.id.et_name);
        EditText etPhone = view.findViewById(R.id.et_phone);

        builder.setView(view)
                .setTitle("Add Contact")
                .setPositiveButton("Add", (dialog, which) -> {
                    String name = etName.getText().toString().trim();
                    String phone = etPhone.getText().toString().trim();
                    if (!name.isEmpty() && !phone.isEmpty()) {
                        addContact(new Contact(name, phone));
                    } else {
                        Toast.makeText(this, "Please enter name and phone", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addContact(Contact contact) {
        String phone = contact.getPhone().trim();
        if (!phone.startsWith("+")) {
            if (phone.startsWith("0")) {
                phone = "+880" + phone.substring(1);
            } else {
                phone = "+880" + phone;
            }
        }
        contact.setPhone(phone);
        dbRef.push().setValue(contact)
                .addOnSuccessListener(unused -> Toast.makeText(this, "Contact added!", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this, "Failed to add contact", Toast.LENGTH_SHORT).show());
    }

    private void deleteContact(String contactId) {
        dbRef.child(contactId).removeValue()
                .addOnSuccessListener(unused -> Toast.makeText(this, "Contact deleted", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this, "Failed to delete contact", Toast.LENGTH_SHORT).show());
    }

    private void checkContactPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_CONTACTS},
                    REQUEST_CONTACT_PERMISSION);
        } else {
            openContactPicker();
        }
    }

    private void openContactPicker() {
        Intent intent = new Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI);
        startActivityForResult(intent, REQUEST_PICK_CONTACT);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CONTACT_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openContactPicker();
            } else {
                Toast.makeText(this, "Permission denied to read contacts", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void editContact(Contact contact) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_add_contact, null);
        EditText etName = view.findViewById(R.id.et_name);
        EditText etPhone = view.findViewById(R.id.et_phone);

        etName.setText(contact.getName());
        etPhone.setText(contact.getPhone());

        builder.setView(view)
                .setTitle("Edit Contact")
                .setPositiveButton("Update", (dialog, which) -> {
                    String newName = etName.getText().toString().trim();
                    String newPhone = etPhone.getText().toString().trim();

                    if (!newName.isEmpty() && !newPhone.isEmpty()) {
                        if (!newPhone.startsWith("+")) {
                            if (newPhone.startsWith("0")) {
                                newPhone = "+880" + newPhone.substring(1);
                            } else {
                                newPhone = "+880" + newPhone;
                            }
                        }

                        contact.setName(newName);
                        contact.setPhone(newPhone);
                        dbRef.child(contact.getId()).setValue(contact)
                                .addOnSuccessListener(unused -> Toast.makeText(this, "Contact updated", Toast.LENGTH_SHORT).show())
                                .addOnFailureListener(e -> Toast.makeText(this, "Failed to update contact", Toast.LENGTH_SHORT).show());
                    } else {
                        Toast.makeText(this, "Please enter name and phone", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_CONTACT && resultCode == Activity.RESULT_OK && data != null) {
            Uri contactUri = data.getData();
            if (contactUri == null) {
                Toast.makeText(this, "Failed to get contact", Toast.LENGTH_SHORT).show();
                return;
            }

            String id = null, name = null, phone = null;
            Cursor cursor = getContentResolver().query(contactUri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                id = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID));
                name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME));
                int hasPhoneNumber = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER));
                if (hasPhoneNumber > 0) {
                    Cursor phoneCursor = getContentResolver().query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                            new String[]{id}, null);
                    if (phoneCursor != null && phoneCursor.moveToFirst()) {
                        phone = phoneCursor.getString(phoneCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER));
                        phoneCursor.close();
                    }
                }
                cursor.close();
            }

            if (name != null && phone != null) {
                addContact(new Contact(name, phone));
            } else {
                Toast.makeText(this, "Failed to get contact details", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
