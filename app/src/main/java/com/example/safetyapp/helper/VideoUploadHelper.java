package com.example.safetyapp.helper;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.telephony.SmsManager;
import android.util.Log;

import com.example.safetyapp.Contact;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoUploadHelper {

    private static final String TAG = "VideoUploadHelper";
    private final Context context;
    private final DatabaseReference userRef;
    private final FirebaseAuth auth;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public interface UploadCallback {
        void onSuccess(String downloadUrl);
        void onFailure(String error);
        void onProgress(int progress);
    }

    public VideoUploadHelper(Context context) {
        this.context = context;
        this.auth = FirebaseAuth.getInstance();
        FirebaseUser user = auth.getCurrentUser();
        if (user != null) {
            this.userRef = FirebaseDatabase.getInstance().getReference("Users").child(user.getUid());
        } else {
            this.userRef = null;
        }
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void uploadVideoAndSendLink(Uri videoUri, UploadCallback callback) {
        Log.i(TAG, "Starting video upload (FREE cloud storage)");

        executor.execute(() -> {
            try {
                String downloadUrl = null;

                // Try GoFile first
                Log.i(TAG, "Trying GoFile.io...");
                String server = getGoFileServer();
                if (server != null) {
                    downloadUrl = uploadToGoFile(server, videoUri, callback);
                }

                // If GoFile fails, try Catbox
                if (downloadUrl == null) {
                    Log.w(TAG, "GoFile failed, trying Catbox.moe...");
                    downloadUrl = uploadToCatbox(videoUri, callback);
                }

                // If Catbox fails, try file.io
                if (downloadUrl == null) {
                    Log.w(TAG, "Catbox failed, trying file.io...");
                    downloadUrl = uploadToFileIO(videoUri, callback);
                }

                // Final result
                if (downloadUrl != null) {
                    Log.i(TAG, "Upload successful! URL: " + downloadUrl);
                    sendVideoLinkToContacts(downloadUrl);
                    final String finalUrl = downloadUrl;
                    mainHandler.post(() -> callback.onSuccess(finalUrl));
                } else {
                    mainHandler.post(() -> callback.onFailure("Upload failed to all servers"));
                }

            } catch (Exception e) {
                Log.e(TAG, "Upload error: " + e.getMessage());
                mainHandler.post(() -> callback.onFailure(e.getMessage()));
            }
        });
    }

    private String getGoFileServer() {
        try {
            URL url = new URL("https://api.gofile.io/servers");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                Log.i(TAG, "GoFile servers response: " + response.toString());

                JSONObject json = new JSONObject(response.toString());
                JSONObject data = json.getJSONObject("data");

                // First try "servers" array
                if (data.has("servers") && data.getJSONArray("servers").length() > 0) {
                    return data.getJSONArray("servers").getJSONObject(0).getString("name");
                }

                // Fallback to "serversAllZone" array
                if (data.has("serversAllZone") && data.getJSONArray("serversAllZone").length() > 0) {
                    return data.getJSONArray("serversAllZone").getJSONObject(0).getString("name");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get GoFile server: " + e.getMessage());
        }
        return null;
    }

    private String uploadToGoFile(String server, Uri videoUri, UploadCallback callback) {
        HttpURLConnection conn = null;
        try {
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            URL url = new URL("https://" + server + ".gofile.io/contents/uploadfile");

            Log.i(TAG, "Uploading to GoFile server: " + server);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(60000);
            conn.setReadTimeout(300000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            String fileName = getFileName(videoUri);
            long fileSize = getFileSize(videoUri);

            Log.i(TAG, "Uploading file: " + fileName + " (" + (fileSize / 1024 / 1024) + " MB)");

            DataOutputStream outputStream = new DataOutputStream(conn.getOutputStream());

            // Write file part
            outputStream.writeBytes("--" + boundary + "\r\n");
            outputStream.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n");
            outputStream.writeBytes("Content-Type: video/mp4\r\n\r\n");

            InputStream inputStream = context.getContentResolver().openInputStream(videoUri);
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesRead = 0;
            int lastProgress = 0;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;

                int progress = (int) ((totalBytesRead * 100) / fileSize);
                if (progress != lastProgress && progress % 5 == 0) {
                    lastProgress = progress;
                    final int p = progress;
                    mainHandler.post(() -> callback.onProgress(p));
                }
            }
            inputStream.close();

            outputStream.writeBytes("\r\n--" + boundary + "--\r\n");
            outputStream.flush();
            outputStream.close();

            int responseCode = conn.getResponseCode();
            Log.i(TAG, "GoFile response code: " + responseCode);

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                Log.i(TAG, "GoFile response: " + response.toString());

                JSONObject json = new JSONObject(response.toString());
                if ("ok".equals(json.getString("status"))) {
                    JSONObject data = json.getJSONObject("data");
                    return data.getString("downloadPage");
                }
            } else {
                Log.e(TAG, "GoFile error response code: " + responseCode);
            }

        } catch (Exception e) {
            Log.e(TAG, "GoFile upload failed: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    private String uploadToCatbox(Uri videoUri, UploadCallback callback) {
        HttpURLConnection conn = null;
        try {
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            URL url = new URL("https://catbox.moe/user/api.php");

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(60000);
            conn.setReadTimeout(300000); // 5 min timeout for large files
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("User-Agent", "SafetyApp/1.0");

            // Get file info
            String fileName = getFileName(videoUri);
            long fileSize = getFileSize(videoUri);

            Log.i(TAG, "Uploading to Catbox: " + fileName + " (" + (fileSize / 1024 / 1024) + " MB)");

            DataOutputStream outputStream = new DataOutputStream(conn.getOutputStream());

            // Write reqtype field
            outputStream.writeBytes("--" + boundary + "\r\n");
            outputStream.writeBytes("Content-Disposition: form-data; name=\"reqtype\"\r\n\r\n");
            outputStream.writeBytes("fileupload\r\n");

            // Write file part
            outputStream.writeBytes("--" + boundary + "\r\n");
            outputStream.writeBytes("Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"" + fileName + "\"\r\n");
            outputStream.writeBytes("Content-Type: video/mp4\r\n\r\n");

            // Write file content with progress tracking
            InputStream inputStream = context.getContentResolver().openInputStream(videoUri);
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesRead = 0;
            int lastProgress = 0;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;

                int progress = (int) ((totalBytesRead * 100) / fileSize);
                if (progress != lastProgress && progress % 5 == 0) {
                    lastProgress = progress;
                    final int p = progress;
                    mainHandler.post(() -> callback.onProgress(p));
                }
            }
            inputStream.close();

            outputStream.writeBytes("\r\n--" + boundary + "--\r\n");
            outputStream.flush();
            outputStream.close();

            // Read response
            int responseCode = conn.getResponseCode();
            Log.i(TAG, "Catbox response code: " + responseCode);

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String result = response.toString().trim();
                Log.i(TAG, "Catbox response: " + result);

                // Catbox returns direct URL on success
                if (result.startsWith("https://")) {
                    return result;
                }
            } else {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                StringBuilder error = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line);
                }
                reader.close();
                Log.e(TAG, "Catbox error: " + error.toString());
            }

        } catch (Exception e) {
            Log.e(TAG, "Catbox upload failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    private String uploadToFileIO(Uri videoUri, UploadCallback callback) {
        HttpURLConnection conn = null;
        try {
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            URL url = new URL("https://file.io");

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(60000);
            conn.setReadTimeout(300000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            String fileName = getFileName(videoUri);
            long fileSize = getFileSize(videoUri);

            Log.i(TAG, "Uploading to file.io: " + fileName);

            DataOutputStream outputStream = new DataOutputStream(conn.getOutputStream());

            // Write file part
            outputStream.writeBytes("--" + boundary + "\r\n");
            outputStream.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n");
            outputStream.writeBytes("Content-Type: video/mp4\r\n\r\n");

            InputStream inputStream = context.getContentResolver().openInputStream(videoUri);
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesRead = 0;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
                int progress = (int) ((totalBytesRead * 100) / fileSize);
                final int p = progress;
                mainHandler.post(() -> callback.onProgress(p));
            }
            inputStream.close();

            outputStream.writeBytes("\r\n--" + boundary + "--\r\n");
            outputStream.flush();
            outputStream.close();

            int responseCode = conn.getResponseCode();
            Log.i(TAG, "file.io response code: " + responseCode);

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                Log.i(TAG, "file.io response: " + response.toString());

                JSONObject json = new JSONObject(response.toString());
                if (json.getBoolean("success")) {
                    return json.getString("link");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "file.io upload failed: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    private String getFileName(Uri uri) {
        String result = "emergency_video.mp4";
        if (uri.getScheme().equals("content")) {
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } finally {
                if (cursor != null) cursor.close();
            }
        }
        return result;
    }

    private long getFileSize(Uri uri) {
        long size = 0;
        if (uri.getScheme().equals("content")) {
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (sizeIndex >= 0) {
                        size = cursor.getLong(sizeIndex);
                    }
                }
            } finally {
                if (cursor != null) cursor.close();
            }
        }
        return size;
    }

    private void sendVideoLinkToContacts(String videoUrl) {
        if (userRef == null) {
            Log.e(TAG, "User not logged in, cannot send video link");
            return;
        }

        // Fetch emergency contacts from Firebase Realtime Database
        userRef.child("emergencyContacts").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                List<Contact> contacts = new ArrayList<>();
                for (DataSnapshot contactSnapshot : snapshot.getChildren()) {
                    Contact contact = contactSnapshot.getValue(Contact.class);
                    if (contact != null) {
                        contacts.add(contact);
                    }
                }

                if (contacts.isEmpty()) {
                    Log.w(TAG, "No emergency contacts found");
                    return;
                }

                String message = "EMERGENCY VIDEO EVIDENCE:\n" + videoUrl +
                        "\n\nPlease see the attached Video";

                for (Contact contact : contacts) {
                    sendSms(contact.getPhone(), message);
                }
                Log.i(TAG, "Video link sent to " + contacts.size() + " contacts");
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Failed to fetch contacts: " + error.getMessage());
            }
        });
    }

    private void sendSms(String phone, String message) {
        try {
            SmsManager smsManager = SmsManager.getDefault();

            if (message.length() > 160) {
                ArrayList<String> parts = smsManager.divideMessage(message);
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null);
            } else {
                smsManager.sendTextMessage(phone, null, message, null, null);
            }

            Log.i(TAG, "Video link SMS sent to: " + phone);

        } catch (Exception e) {
            Log.e(TAG, "Failed to send SMS to " + phone + ": " + e.getMessage());
        }
    }
}
