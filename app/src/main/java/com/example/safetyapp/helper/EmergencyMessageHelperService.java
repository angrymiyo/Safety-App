package com.example.safetyapp.helper;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.example.safetyapp.Contact;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.List;

public class EmergencyMessageHelperService {

    private static final String TAG = "EmergencyMsgHelperSvc";

    public static void sendEmergencyMessages(Context context, String customMessage) {
        if (!hasPermissions(context)) {
            Log.e(TAG, "Required permissions not granted");
            return;
        }

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            Log.e(TAG, "User not logged in");
            return;
        }

        DatabaseReference dbRef = FirebaseDatabase.getInstance()
                .getReference("Users")
                .child(currentUser.getUid())
                .child("emergencyContacts");

        dbRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                List<Contact> contactList = new ArrayList<>();
                for (DataSnapshot contactSnap : snapshot.getChildren()) {
                    Contact contact = contactSnap.getValue(Contact.class);
                    if (contact != null) {
                        contactList.add(contact);
                    }
                }

                if (contactList.isEmpty()) {
                    Log.e(TAG, "No emergency contacts found");
                    return;
                }

                requestCurrentLocationAndSendSms(context, contactList, customMessage);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Failed to load contacts", error.toException());
            }
        });
    }

    @SuppressLint("MissingPermission") // permission is already checked in hasPermissions
    private static void requestCurrentLocationAndSendSms(Context context, List<Contact> contacts, String customMessage) {
        FusedLocationProviderClient locationClient = LocationServices.getFusedLocationProviderClient(context);

        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationRequest.setNumUpdates(1);
        locationRequest.setInterval(0);

        LocationCallback locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                String locationText = "[Location not available]";
                if (location != null) {
                    locationText = "https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
                }
                String finalMessage = customMessage + " " + locationText;

                SmsManager smsManager = SmsManager.getDefault();
                for (Contact contact : contacts) {
                    try {
                        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                            smsManager.sendTextMessage(contact.getPhone(), null, finalMessage, null, null);
                            Log.i(TAG, "SMS sent to " + contact.getPhone());
                        } else {
                            Log.e(TAG, "SEND_SMS permission denied. Cannot send SMS to " + contact.getPhone());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to send SMS to " + contact.getPhone(), e);
                    }
                }

                // Remove location updates after getting the location once
                locationClient.removeLocationUpdates(this);
            }
        };

        locationClient.requestLocationUpdates(locationRequest, locationCallback, null);
    }

    private static boolean hasPermissions(Context context) {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
}
