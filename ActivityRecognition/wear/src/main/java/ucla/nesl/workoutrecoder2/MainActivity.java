package ucla.nesl.workoutrecoder2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.content.LocalBroadcastManager;
import android.support.wearable.activity.WearableActivity;
import android.support.wearable.view.WatchViewStub;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.TextView;

public class MainActivity extends WearableActivity {
    private TextView mTextView;

    // private BGDataCollectionService serviceInstance = null;
    private static final String TAG = "Activity";

    // Set format for date and time
    private static final TimeString mTimestring = new TimeString();

    // Animation time
    private final long FLASHING_PERIOD = 500;  // ms
    private final long GRIVATY_WAITING_PERIOD = 15000;  // ms, should receive event every 10 secs, 5 sec tolerence

    // UI elements
    private RelativeLayout mainLayout;

    // Current state of the recording
    private boolean mTracking = false;
    private String mTime = null;
    private TextView mAccCounterTextView;
    private static int mSensorCounter = 0;
    private Button startButton;
    private Button stopButton;

    private Handler handler = new Handler();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final WatchViewStub stub = (WatchViewStub) findViewById(R.id.watch_view_stub);
        stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
            @Override
            public void onLayoutInflated(WatchViewStub stub) {
                mTextView = (TextView) stub.findViewById(R.id.text);
                mAccCounterTextView = (TextView) stub.findViewById(R.id.text1);

                mainLayout = (RelativeLayout) stub.findViewById(R.id.layout);
                startButton = (Button) stub.findViewById(R.id.button1);
                stopButton = (Button) stub.findViewById(R.id.button2);

                // Get saved recording state from shared preference
                SharedPreferences sharedPref = getSharedPreferences(
                        getString(R.string.preference_file_key), Context.MODE_PRIVATE);
                mTracking = sharedPref.getBoolean(getString(R.string.saved_rec_flag), false);
                if (mTracking) {
                    mTime = sharedPref.getString(getString(R.string.saved_rec_time), "");
                    mTextView.setText("Tracking started at " + mTime);
                    startButton.setEnabled(false);
                } else {
                    mTextView.setText("Tracking stopped");
                    stopButton.setEnabled(false);
                }
            }
        });

        if (!mTracking) {
            // Start the service
            Intent intent = new Intent(this, BGDataCollectionService.class);
            startService(intent);
        }
    }
    public void onStartClicked(View view) {
        Log.i(TAG, "start clicked");
        if (!mTracking) {
            mTracking = true;
            mTime = mTimestring.currentTimeForDisplay();
            BGDataCollectionService.startRecording(mTimestring.currentTimeForFile());
            mTextView.setText("Started at " + mTime);
            gravityWatchDog.start();
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
        }
        else {
            Log.w(TAG, "Tracking already started!");
        }
    }

    public void onStopClicked(View view) {
        Log.i(TAG, "stop clicked");
        if (mTracking) {
            BGDataCollectionService.stopRecording();
            mTextView.setText("Tracking stopped (" + mTime + ")");
            mTracking = false;
            mTime = null;
            warningScreenFlash.stop();
            gravityWatchDog.stop();
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
        }
        else {
            Log.w(TAG, "Tracking already stopped!");
        }
    }

    protected void onPause() {
        super.onPause();
        // Unbind to service on pause
//        unbindService(mConnection);
//        serviceInstance = null;
        // Save current recording state to shared preference
        SharedPreferences sharedPref = getSharedPreferences(
                getString(R.string.preference_file_key), Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putBoolean(getString(R.string.saved_rec_flag), mTracking);
        if (mTracking) {
            editor.putString(getString(R.string.saved_rec_time), mTime);
        }
        else {
            editor.putString(getString(R.string.saved_rec_time), null);
        }
        editor.commit();

        // Unregister broadcast receiver
        LocalBroadcastManager bManager = LocalBroadcastManager.getInstance(this);
        bManager.unregisterReceiver(accCounterReceiver);
        Log.i(TAG, "Receiver un-registered.");
    }

    protected void onResume() {
        super.onResume();

//        // Bind to service on resume
//        Intent intent= new Intent(this, BGDataCollectionService.class);
//        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        // Register broadcast receiver
        LocalBroadcastManager bManager = LocalBroadcastManager.getInstance(this);
        IntentFilter intentFilter = new IntentFilter();
        //intentFilter.addAction(BGDataCollectionService.ACC_1K_ACTION);
        intentFilter.addAction(BGDataCollectionService.GRAV_250_ACTION);
        intentFilter.addAction(BGDataCollectionService.GRAV_VALUE_UNCHANGED_WARNING);
        bManager.registerReceiver(accCounterReceiver, intentFilter);
        Log.i(TAG, "Receiver registered.");
    }

//    private ServiceConnection mConnection = new ServiceConnection() {
//        public void onServiceConnected(ComponentName className, IBinder b) {
//            BGDataCollectionService.MyBinder binder = (BGDataCollectionService.MyBinder) b;
//            serviceInstance = binder.getService();
//            Log.i(TAG, "service connected");
//        }
//
//        public void onServiceDisconnected(ComponentName className) {
//            serviceInstance = null;
//            Log.i(TAG, "service DISconnected");
//        }
//    };

    private BroadcastReceiver accCounterReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "broadcast received " + intent.getAction());
            if (intent.getAction().equals(BGDataCollectionService.GRAV_250_ACTION)) {
                mSensorCounter += 250;
                mAccCounterTextView.setText(String.format("Grav: %.2fk @%s", mSensorCounter / 1000f,
                        mTimestring.currentTimeOnlyForDisplay()));
                gravityWatchDog.cease();
            } else if (intent.getAction().equals(BGDataCollectionService.GRAV_VALUE_UNCHANGED_WARNING)) {
                mAccCounterTextView.setText(String.format("FATAL: gravity values are not updated"));
            }
        }
    };


    private class WarningScreenFlash {
        private boolean isFlashing = false;
        private int currentColor = Color.BLACK;

        public void start() {
            if (!isFlashing) {
                handler.postDelayed(flashProcedure, FLASHING_PERIOD);
                isFlashing = true;
            }
        }

        public void stop() {
            isFlashing = false;
            handler.removeCallbacks(flashProcedure);
            currentColor = Color.BLACK;
            mainLayout.setBackgroundColor(Color.BLACK);
        }

        private Runnable flashProcedure = new Runnable() {
            @Override
            public void run() {
                if (currentColor == Color.BLACK) {
                    currentColor = Color.RED;
                } else {
                    currentColor = Color.BLACK;
                }
                mainLayout.setBackgroundColor(currentColor);
                handler.postDelayed(flashProcedure, FLASHING_PERIOD);
            }
        };
    }
    private WarningScreenFlash warningScreenFlash = new WarningScreenFlash();


    private class GravityCounterWatchDog {
        private boolean flagWarningSent = false;

        public void start() {
            if (!flagWarningSent)
                handler.postDelayed(raiseWarningTimer, GRIVATY_WAITING_PERIOD);
        }

        public void cease() {
            handler.removeCallbacks(raiseWarningTimer);
            if (!flagWarningSent)
                handler.postDelayed(raiseWarningTimer, GRIVATY_WAITING_PERIOD);
        }

        public void stop() {
            flagWarningSent = false;
            handler.removeCallbacks(raiseWarningTimer);
        }

        private Runnable raiseWarningTimer = new Runnable() {
            @Override
            public void run() {
                flagWarningSent = true;
                warningScreenFlash.start();
            }
        };
    }
    private GravityCounterWatchDog gravityWatchDog = new GravityCounterWatchDog();
}
