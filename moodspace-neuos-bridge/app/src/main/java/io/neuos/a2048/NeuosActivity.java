package io.neuos.a2048;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.util.List;

import io.neuos.INeuosSdk;
import io.neuos.INeuosSdkListener;
import io.neuos.NeuosQAProperties;
import io.neuos.NeuosSDK;

public class NeuosActivity extends AppCompatActivity {

    private Button launchButton;
    private AlertDialog loadingDialog;
    final String TAG = "Neuos SDK";
    // TODO: This API key should be elsewhere
    final String API_KEY = "8AXRD2RBBCPjJYu3q";
    private INeuosSdk mService;
    private Runnable mPostConnection;
    private DeviceConnectionReceiver deviceListReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Register to receive device pairing callbacks
        deviceListReceiver = new DeviceConnectionReceiver();
        registerReceiver(deviceListReceiver,
                new IntentFilter(NeuosSDK.IO_NEUOS_DEVICE_PAIRING_ACTION));
        // Start the flow by verifying the permissions to use the SDK
        checkSDKPermissions();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(R.layout.progress_dialog);
        // This should be called once in your Fragment's onViewCreated() or in Activity onCreate() method to avoid dialog duplicates.
        loadingDialog = builder.create();
        setContentView(R.layout.activity_neuos);
        launchButton = findViewById(R.id.launch);
    }

    @Override
    protected void onDestroy() {
        try {
            // clean up the service
            mService.shutdownSdk();
            unregisterReceiver(deviceListReceiver);

        } catch (RemoteException e) {
            e.printStackTrace();
        }
        super.onDestroy();
    }

    public void onLaunchClick(View view) {
        launchButton.setEnabled(false);
        // This begins the flow of login -> check calibration -> start session -> Qa -> Game
        checkNeuosLoginStatus();
    }

    // Activity launcher from login
    private final ActivityResultLauncher<Intent> gameLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    loadingDialog.show();
                    finishSession();
                }
            });

    // Callback class for device connections from Neuos
    private class DeviceConnectionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String address = intent.getStringExtra(io.neuos.NeuosSDK.DEVICE_ADDRESS_EXTRA);
            Log.d(TAG, "Connection Intent : " + address);
            try {
                mService.connectSensorDevice(address);
            } catch (RemoteException e) {
                Log.e(TAG, e.getLocalizedMessage());
            }
        }
    }
    // Neuos SDK Listener
    private final INeuosSdkListener mCallback = new INeuosSdkListener.Stub() {
        @Override
        public void onConnectionChanged(int previousConnection, int currentConnection) throws RemoteException {
            Log.i(TAG, "onConnectionChanged P: " + previousConnection + " C: " + currentConnection);
            if (currentConnection == NeuosSDK.ConnectionState.CONNECTED){
                if (mPostConnection != null){
                    mPostConnection.run();
                    mPostConnection = null;
                }
            }
        }
        @Override
        public void onValueChanged(String key, float value) throws RemoteException {
            Log.i(TAG, "onValueChanged K: " + key + " V: " + value);
            // update our view with proper values

        }
        @Override
        public void onQAStatus(boolean passed , int type){
            Log.i(TAG, "on QA Passed: " + passed + " T: " + type);
        }

        @Override
        public void onSessionComplete() throws RemoteException {
            Log.i(TAG, "onSessionComplete");
            // Once the session upload is complete, hide the dialog and allow re-launch
            runOnUiThread( () -> {
                    loadingDialog.dismiss();
                    launchButton.setEnabled(true);
            });
        }


        @Override
        public void onError(int errorCode, String message) throws RemoteException {
            Log.i(TAG, "onError Code: " + errorCode + " " + message);
        }
    };

    // Activity launcher from login
    private final ActivityResultLauncher<Intent> loginResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    checkCalibrationStatus();
                }
            });
    // Activity launcher for QA result
    private final ActivityResultLauncher<Intent> qaResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != Activity.RESULT_OK) {
                    // Here you should work with your user to get his headband working
                    showError("QA Failed!");
                    // Or terminate the session
                    try {
                        mService.terminateSessionQaFailed();
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
                else{
                    // we are good to go, launch the game
                    gameLauncher.launch(new Intent(this , MainActivity.class));
                }
            });
    // Activity launcher for permissions request
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    // Permission is granted. Continue the action or workflow in your app
                    doBindService();
                } else {
                    // Explain to the user that the feature is unavailable because the
                    // features requires a permission that the user has denied. At the
                    // same time, respect the user's decision. Don't link to system
                    // settings in an effort to convince the user to change their
                    // decision.
                }
            });

    // Helper function that tests if we have permissions
    // and starts the binding process or requests the permissions
    private void checkSDKPermissions(){
        if (ContextCompat.checkSelfPermission(
                this, NeuosSDK.NEUOS_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED) {
            // You can use the API that requires the permission.
            doBindService();
        } else {
            // You can directly ask for the permission.
            // The registered ActivityResultCallback gets the result of this request.
            requestPermissionLauncher.launch(
                    NeuosSDK.NEUOS_PERMISSION);
        }
    }

    /**
     * Class for interacting with the main interface of the service.
     */
    private final ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            Log.d(TAG, "Attached.");
            mService = INeuosSdk.Stub.asInterface(service);
            try {
                int response = mService.initializeNeuos(API_KEY);
                if ( response != NeuosSDK.ResponseCodes.SUCCESS){
                    Log.i(TAG, "initialize: failed with code " + response);
                    showError("initialize: failed with code " + response);
                }
                else{
                    response = mService.registerSDKCallback(mCallback);
                    if ( response != NeuosSDK.ResponseCodes.SUCCESS){
                        Log.i(TAG, "registerSDKCallback: failed with code " + response);
                        showError("registerSDKCallback failed with code " + response);
                    }
                    Log.i(TAG, "register callback: returned with code " + response);
                }
            } catch (RemoteException e) {
                Log.e(TAG, e.getLocalizedMessage());
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "Detached.");
        }
    };

    // Check the user calibration status before starting a session
    private void checkCalibrationStatus(){
        try {
            int calibrationStatus = mService.checkUserCalibrationStatus();
            Log.i(TAG, "onUserCalibrationStatus: " + calibrationStatus);
            switch (calibrationStatus) {
                case NeuosSDK.UserCalibrationStatus.NEEDS_CALIBRATION:
                    // Here you can instead launch the calibration activity for the user.
                    // We don't do this in this context, but you can do this using the code in startCalibration()
                    showError("User is not calibrated. Cannot perform realtime stream");
                    break;
                case NeuosSDK.UserCalibrationStatus.CALIBRATION_DONE:
                    showError("Models for this user have yet to be processed");
                    break;
                case NeuosSDK.UserCalibrationStatus.MODELS_AVAILABLE:
                    connectToDevice();
                    mPostConnection = () -> {
                        // Start a session
                        if (startSession() == NeuosSDK.ResponseCodes.SUCCESS) {
                            // once started successfully, launch QA screen
                            launchQAScreen();
                        }
                    };
                    break;
            }
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage());
        }
    }
    // Start a session
    private int startSession() {
        int response = -1;
        try {
            response = mService.startPredictionSession(NeuosSDK.Predictions.ZONE);
            if ( response != NeuosSDK.ResponseCodes.SUCCESS){
                Log.i(TAG, "startSession: failed with code " + response);
                showError("startSession: failed with code " + response);
            }
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage());
        }
        return response;
    }

    // Starts the process of connecting to a device
    private void connectToDevice(){
        Intent explicit = getExplicitIntent(new Intent(NeuosSDK.NEUOS_PAIR_DEVICE));
        if (explicit == null){
            showError("Cannot find Neuos pair device activity");
            return;
        }
        startActivity(explicit);
    }

    // Check the login status of a user
    private void checkNeuosLoginStatus() {
        try {
            int status = mService.getUserLoginStatus();
            switch (status){
                case NeuosSDK.LoginStatus.LOGGED_IN:{
                    Log.i(TAG, "login: Logged In");
                    checkCalibrationStatus();
                    break;
                }
                case NeuosSDK.LoginStatus.NOT_LOGGED_IN:{
                    Log.i(TAG, "login: Not Logged In");
                    launchLogin();
                    break;
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage());
        }
    }
    // Launches the Neuos Login activity
    private void launchLogin(){
        Intent explicit = getExplicitIntent(new Intent(NeuosSDK.NEUOS_LOGIN));
        if (explicit == null){
            showError("Cannot find Neuos Login activity");
            return;
        }
        loginResultLauncher.launch(explicit);
    }
    // Launches the QA activity
    private void launchQAScreen() {
        Intent explicit = getExplicitIntent(new Intent(NeuosSDK.NEUOS_QA_SCREEN));
        if (explicit == null){
            showError("Cannot find QA activity");
            return;
        }
        explicit.putExtra(NeuosQAProperties.STAND_ALONE , true);
        explicit.putExtra(NeuosQAProperties.TASK_PROPERTIES ,
                new NeuosQAProperties(NeuosQAProperties.Quality.Normal , NeuosQAProperties.INFINITE_TIMEOUT));
        qaResultLauncher.launch(explicit);
    }
    // Binds to Neuos Service
    private boolean doBindService() {
        try {
            // Create an intent based on the class name
            Intent serviceIntent = new Intent(INeuosSdk.class.getName());
            // Use package manager to find intent reciever
            List<ResolveInfo> matches=getPackageManager()
                    .queryIntentServices(serviceIntent, 0);
            if (matches.size() == 0) {
                Log.d(TAG, "Cannot find a matching service!");
                showError("Cannot find a matching service!");
            }
            else if (matches.size() > 1) {
                // This is really just a sanity check
                // and should never occur in a real life scenario
                showError("Found multiple matching services!");
            }
            else {
                // Create an explicit intent
                Intent explicit=new Intent(serviceIntent);
                ServiceInfo svcInfo=matches.get(0).serviceInfo;
                ComponentName cn=new ComponentName(svcInfo.applicationInfo.packageName,
                        svcInfo.name);
                explicit.setComponent(cn);
                // Bind using AUTO_CREATE
                if (bindService(explicit, mConnection,  BIND_AUTO_CREATE)){
                    Log.d(TAG, "Bound to Neuos Service");
                } else {
                    Log.d(TAG, "Failed to bind to Neuos Service");
                    showError("Failed to bind to Neuos Service");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "can't bind to NeuosService, check permission in Manifest");
            showError("can't bind to NeuosService, check permission in Manifest");
        }
        return false;
    }
    // closes the session and uploads to clound
    private void finishSession() {
        try {
            mService.finishSession();
        } catch (RemoteException e) {
            Log.e(TAG, "finishSession: ", e);
        }
    }

    private void showError(String message){
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // Use this to launch a calibration session in case your user isn't calibrated
    private void startCalibration() {
        Intent explicit = getExplicitIntent(new Intent(NeuosSDK.NEUOS_CALIBRATION));
        if (explicit != null) {
            startActivity(explicit);
        }
    }

    private Intent getExplicitIntent(Intent activityIntent){
        List<ResolveInfo> matches=getPackageManager()
                .queryIntentActivities(activityIntent , PackageManager.MATCH_ALL);
        // if we couldn't find one, return null
        if (matches.isEmpty()) {
            return null;
        }
        // there really should only be 1 match, so we get the first one.
        Intent explicit=new Intent(activityIntent);
        ActivityInfo info = matches.get(0).activityInfo;
        ComponentName cn = new ComponentName(info.applicationInfo.packageName,
                info.name);
        explicit.setComponent(cn);
        return explicit;
    }


}