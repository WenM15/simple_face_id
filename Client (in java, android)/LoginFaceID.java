package utar.my.e_wallet;

import static utar.my.e_wallet.MyApp.databaseUsersReference;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LoginFaceID extends AppCompatActivity {

    private boolean imageLock;
    private boolean faceIdStatus = false;
    private String userId;
    private Button btnConfirm;
    private EditText emailText;

    private CameraManager cameraManager;
    private String[] cameraIdList;
    private String frontCameraId;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final int REQUEST_CAMERA_PERMISSION = 1;
    private CaptureRequest.Builder previewRequestBuilder;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private CameraDevice frontCamera;
    private CameraCaptureSession frontCameraSession;
    private ImageReader imageReader;
    private CameraCharacteristics cameraCharacteristics;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login_face_id);

        startBackgroundThread();

        // Initialize camera manager
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        // Get all camera id
        try {
            cameraIdList = cameraManager.getCameraIdList();
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }

        // Get camera characteristics of each id, find the id with front lens
        for (String id : cameraIdList)
        {
            try {
                cameraCharacteristics = cameraManager.getCameraCharacteristics(id);
                Integer lensFacing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                if (lensFacing != null)
                {
                    if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT)
                    {
                        frontCameraId = id;
                        System.out.println(frontCameraId);
                        break;
                    }
                }
            } catch (CameraAccessException e) {
                throw new RuntimeException(e);
            }
        }

        setupSurfaceView();

        btnConfirm = findViewById(R.id.btnConfirm);
        emailText = findViewById(R.id.editTextTextEmailAddress);
        btnConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String email = emailText.getText().toString().trim();

                if (TextUtils.isEmpty(email)) {
                    Toast.makeText(LoginFaceID.this, "Enter email", Toast.LENGTH_SHORT).show();
                    return;
                }

                findUserByEmail(email);
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopBackgroundThread();
    }

    public void findUserByEmail(String email) {
        // Query the database to find the user with the specified email
        Query query = databaseUsersReference.orderByChild("email").equalTo(email);
        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                                userId = snapshot.getKey();

                                int tries = 1;
                                while (tries <= 40) {
                                    if (faceIdStatus)
                                    {
                                        break;
                                    }
                                    if (imageLock == false) {
                                        CaptureStillImage();
                                    }
                                    try {
                                        Thread.sleep(500);  // Postpone for 2 seconds before another loop
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                    ++tries;
                                }

                                if (faceIdStatus)
                                {
                                    requestToken(userId);
                                }
                                else
                                {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            Toast.makeText(LoginFaceID.this, "FaceId verification failed", Toast.LENGTH_SHORT).show();
                                            Intent intent = new Intent(LoginFaceID.this, LoginActivity.class);
                                            startActivity(intent);
                                        }
                                    });
                                }
                            }
                        }
                    }).start();

                } else {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(LoginFaceID.this, "No user found with email: " + email, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }


            @Override
            public void onCancelled(DatabaseError databaseError) {
                System.err.println("Database error: " + databaseError.getCode());
            }
        });
    }

    private void startBackgroundThread() {
        if (backgroundThread == null || !backgroundThread.isAlive()) {
            backgroundThread = new HandlerThread("CameraBackground");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
                // Restore the interrupted status
                Thread.currentThread().interrupt();
            } finally {
                backgroundThread = null;
                backgroundHandler = null;
            }
        }
    }

    private void requestToken(String userId) {
        new Thread(() -> {
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .url("http://" + ServerAddress.getIpAddress() +":5000/generate")
                    .header("userid", userId)
                    .post(RequestBody.create("", null)) // Empty POST request
                    .build();

            try {
                Response response = client.newCall(request).execute(); // Execute the request synchronously
                if (response.isSuccessful() && response.body() != null) {
                    String jsonResponse = response.body().string();  // Get the JSON response as a string
                    try {
                        JSONObject jsonObject = new JSONObject(jsonResponse);
                        String token = jsonObject.getString("token");  // Extract the token using the key
                        runOnUiThread(() -> signInWithToken(token));
                    } catch (JSONException e) {
                        Log.d("Token", "JSON parsing error: " + e.getMessage());
                        runOnUiThread(() -> Toast.makeText(LoginFaceID.this, "Token parsing error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                } else {
                    String errorMessage = response.body() != null ? response.body().string() : "Unknown error";
                    Log.d("Token", "Failed to retrieve token: " + errorMessage);
                    runOnUiThread(() -> Toast.makeText(LoginFaceID.this, "Failed to retrieve token: " + errorMessage, Toast.LENGTH_LONG).show());
                }
            } catch (IOException e) {
                Log.d("Token", "Failed to retrieve token: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(LoginFaceID.this, "Failed to request token: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void signInWithToken(String token) {
        FirebaseAuth.getInstance().signInWithCustomToken(token)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        // Sign-in succeeded, update UI with the signed-in user's information
                        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                        Toast.makeText(LoginFaceID.this, "User signed in successfully", Toast.LENGTH_LONG).show();
                        Intent intent = new Intent(LoginFaceID.this, Homepage.class);
                        startActivity(intent);
                    } else {
                        // If sign in fails, display a message to the user.
                        Log.d("Token", "Authentication failed: " + task.getException().getMessage());
                        Toast.makeText(LoginFaceID.this, "Authentication failed: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                        Intent intent = new Intent(LoginFaceID.this, LoginActivity.class);
                        startActivity(intent);
                    }
                });
    }

    private void setupSurfaceView() {
        surfaceView = findViewById(R.id.surfaceView3);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                try {
                    AttemptToOpenCamera();
                } catch (CameraAccessException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                // Handle changes if necessary
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                // Close camera and release resources here
            }
        });
    }

    private void AttemptToOpenCamera() throws CameraAccessException {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted, so request it
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;  // Exit this method to prevent further execution
        }
        try {
            OpenCameraWrapper();
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission was granted
                try {
                    OpenCameraWrapper();
                } catch (CameraAccessException e) {
                    throw new RuntimeException(e);
                }
            } else {
                // Permission was denied
                Toast.makeText(this, "Camera permission is necessary to register Face ID", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void OpenCameraWrapper() throws CameraAccessException {
        CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                frontCamera = camera;
                InitializeImageReader();
                StartPreview();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                camera.close();
                Toast.makeText(LoginFaceID.this, "Camera disconnected.", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                camera.close();
                Toast.makeText(LoginFaceID.this, "Error opening camera: " + error, Toast.LENGTH_SHORT).show();
            }
        };

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted, so request it
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;  // Exit this method to prevent further execution
        }
        cameraManager.openCamera(frontCameraId, stateCallback, backgroundHandler);
    }

    private void InitializeImageReader() {
        // Assume this size and format is suitable for your needs
        imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                // Handle the image once available
                Image image = reader.acquireNextImage();
                if (image != null)
                {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    image.close();

                    // PROCESS HERE
                    // PROCESS HERE

                    UploadImage(bytes);
                    // PROCESS HERE
                    // PROCESS HERE
                }
                else
                {
                    imageLock = false;
                }
            }
        }, backgroundHandler); // Handle images on a background thread
    }
    private void StartPreview()
    {
        List<Surface> outputSurfaces = new ArrayList<>();
        Surface previewSurface = surfaceHolder.getSurface();
        outputSurfaces.add(previewSurface);
        outputSurfaces.add(imageReader.getSurface());

        try {
            frontCamera.createCaptureSession(outputSurfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (null == frontCamera) {
                                return; // camera is already closed
                            }
                            frontCameraSession = session;
                            try {
                                previewRequestBuilder = frontCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                previewRequestBuilder.addTarget(previewSurface);
                                previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

                                session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(LoginFaceID.this, "Failed to set up camera preview.", Toast.LENGTH_SHORT).show();
                        }
                    }, backgroundHandler);
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }

    private void CaptureStillImage() {
        imageLock = true;
        try {
            CaptureRequest.Builder captureBuilder = frontCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());

            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
//            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
//            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            int rotation = ((Activity) LoginFaceID.this).getWindowManager().getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0: degrees = 0; break;
                case Surface.ROTATION_90: degrees = 90; break;
                case Surface.ROTATION_180: degrees = 180; break;
                case Surface.ROTATION_270: degrees = 270; break;
            }

            // Assuming 'cameraInfo' holds the CameraCharacteristics object
            int sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int finalOrientation = (sensorOrientation + degrees + 360) % 360;

            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, finalOrientation);

            CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    System.out.println("Image Captured!");
                }
            };

            frontCameraSession.capture(captureBuilder.build(), captureCallback, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.d("FaceID_Register - CaptureStillImage", String.valueOf(e));
            e.printStackTrace();
        }
    }

    private void UploadImage(byte[] imageBytes)
    {
        new Thread(new Runnable() {
            @Override
            public void run() {
                OkHttpClient client = new OkHttpClient();
                RequestBody requestBody = RequestBody.create(
                        MediaType.parse("image/jpeg"), imageBytes);

                Request request = new Request.Builder()
                        .url("http://" + ServerAddress.getIpAddress() +":5000/upload")
                        .header("userid", userId)
                        .post(requestBody)
                        .build();

                try {
                    Response response = client.newCall(request).execute(); // Synchronous call
                    if (response.isSuccessful()) {
                        String responseBody = response.body().string();
                        Log.d("ClientApp", "Response body: " + responseBody);
                        Log.d("ClientApp", "Response code: " + response.code());
                        faceIdStatus = true;
                    } else {
                        Log.d("ClientApp", "Error response " + response.code() + ": " + response.body().string());
                    }
                } catch (Exception e) {
                    Log.d("ClientApp", "Error connecting to server: " + e.getMessage(), e);
                    Toast.makeText(LoginFaceID.this, "Error connecting to server: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                }
                finally
                {
                    Log.d("ClientApp", "Request completed");
                    imageLock = false;
                }
            }
        }).start();
    }
}