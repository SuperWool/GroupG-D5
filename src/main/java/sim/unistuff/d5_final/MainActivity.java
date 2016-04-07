package sim.unistuff.d5_final;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

/**
 * This is the main activity; where everything except for graph plotting is done.
 * A BluetoothSerialService object is used here to send commands to the Il Matto
 * and fetch data from it via Bluetooth.
 * <p>Any graph-plotting is done in a GraphFragment tied to this Activity.
 */

public class MainActivity extends Activity {
	// Message types sent from the BluetoothSerialService Handler
	public static final int MESSAGE_STATE_CHANGE = 1;
	public static final int MESSAGE_READ = 2;
	public static final int MESSAGE_WRITE = 3;
	public static final int MESSAGE_TOAST = 4;	

	// Key names received from the BluetoothSerialService Handler
	public static final String DEVICE_NAME = "Il_Matto";
	public static final String TOAST = "toast";

	// Debug constants. Used for debugging.
	private static final boolean D = true;
	private static final boolean D_TEST_GRAPH = true;
	private static final boolean D_RL_DATA_ONLY = true;
	private static final boolean D_SKIP_TO_GRAPH = false;
	private static final String TAG = "MainActivity";

	// Variables involved in Bluetoothing
	private static final int REQUEST_BT_ENABLE = 10;
	private static final int OPEN_BT_SETTINGS = 11;
	private static final String BT_TARGET_DEVICE = "HC-06";
	private final BluetoothAdapter mBtAdapter = BluetoothAdapter.getDefaultAdapter();
	private final BtHandler mBtHandler = new BtHandler(this);
	private BluetoothSerialService mBtss = null;
	private boolean mStartedGettingSdData = false || D_RL_DATA_ONLY;
	private String mReceiveFromBluetooth = "";

	// Misc variables
	private final File DIR = new File(Environment.getExternalStorageDirectory(),
			"Electric Meter");
	private final Handler mUpdateTimerHandler = new Handler();
	private boolean mDoneGettingSdData = false || D_RL_DATA_ONLY;
	private File mSdDataFile;
	private PrintWriter mWriter;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		if (savedInstanceState == null) {
			getFragmentManager().beginTransaction()
			.add(R.id.container, new GraphFragment())
			.commit();
		}

        if (D_SKIP_TO_GRAPH) {	// This is for testing GraphFragment
            /// Check if there's already a GraphFragment, if not, create a new one
            Fragment f = new GraphFragment();
            Bundle b = new Bundle();
            b.putBoolean("TEST_GRAPH", D_TEST_GRAPH);
            f.setArguments(b);
            getFragmentManager().beginTransaction()
                    .replace(R.id.container, f)
                    .commit();
        } else {
            if (!(mBtAdapter.isEnabled())) {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                        REQUEST_BT_ENABLE);
            } else {
                setupBt();
            }
        }

		if (!(DIR.isDirectory())) DIR.mkdir();
	}

	@Override
	public void onDestroy() {
		if (mBtss != null) {
			mBtss.stop();
			mBtss = null;
		}
		if (mBtAdapter != null) mBtAdapter.disable();
		if (mWriter != null) mWriter.close();
		if (mSdDataFile != null && mSdDataFile.length() == 0) {
			if (mSdDataFile.delete()) {
				if (D) Log.d(TAG, "File deleted");
			} else {
				if (D) Log.w(TAG, "File not deleted");
			}
		}
		if (mUpdateTimerHandler != null)
			mUpdateTimerHandler.removeCallbacks(mUpdateTimerThread);
		super.onDestroy();
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent action) {
		switch (requestCode) {
		case REQUEST_BT_ENABLE:
			if (resultCode == RESULT_OK) {
				setupBt();
			} else {
				if (D) Log.w(TAG, "Bluetooth adapter wasn't enabled. Exiting");
				butter(getApplicationContext(), "Bluetooth not enabled. Exiting");
				long time = System.currentTimeMillis();
				while ((System.currentTimeMillis() - time) < Toast.LENGTH_SHORT);
				finish();
			}
			break;
		case OPEN_BT_SETTINGS:
			setupBt();
			break;
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.activity_main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.restart_connection:
			if (mBtss.getState() == BluetoothSerialService.STATE_NONE) {
				mBtss.start();
				mBtss.connect(getDevice());
				butter(getApplicationContext(), "Reconnecting to meter...");
			}
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	/**
	 * Gets the device in order to do some Bluetooth
	 * @return The Il Matto's BT module
	 */
	private BluetoothDevice getDevice() {
		Set<BluetoothDevice> mmDevices = mBtAdapter.getBondedDevices();
		for (BluetoothDevice e : mmDevices) {
			if (e.getName().equalsIgnoreCase(BT_TARGET_DEVICE)) {
				if (D) Log.d(TAG, "Device found");
				return e;
			}
		}
		return null;
	}

	/**
	 * The method that is called by the request_data button
	 * @param what The command to send to the Il Matto:
	 * <tt>'a'</tt> to retrieve SD data and <tt>'b'</tt> to retrieve RL
	 * data
	 */
	private void sendToDb(char what) {
		if (what == 'a') {
			// Create mSdDataFile and mWriter
			SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy");
			String relativePath = sdf.format(new Date()) + ".txt";
			if (D) Log.d(TAG, "Getting SD data, filename: " + relativePath);
			butter(this, "Getting SD data! Filename:\n" + relativePath);
			mSdDataFile = new File(DIR, relativePath);
			try {
				if (mSdDataFile.createNewFile()) {
					if (mSdDataFile.isFile()) {
						if (D) Log.i(TAG, "mSdDataFile created. Path: "
								+ mSdDataFile.getPath());
					}
				}
				// if the file throws IOException mWriter will be null
				// prepare the defenses if this happens
				mWriter = new PrintWriter(mSdDataFile);
				// Call SD data
				mBtss.write(new byte[]{0x32}); // integer 2 --> SD data
			} catch (Exception e) {
				if (D) Log.w(TAG, e.getMessage());
				if (e.getClass()==FileNotFoundException.class) {
					butter(getApplicationContext(), 
							"File couldn't be opened! Getting real-time data now instead");
				} else if (e.getClass()==IOException.class) {
					butter(getApplicationContext(), 
							"File couldn't be created! Getting real-time data now instead");
				}
				if (mSdDataFile != null) mSdDataFile.delete();
				setSdDataGettingDone();
			}
		} else if (what == 'b') {

			// Call live data
			mBtss.write(new byte[]{0x31}); // integer 1 --> RL data
			mUpdateTimerHandler.post(mUpdateTimerThread);
		}
	}

	/**
	 * A shorter way to make Toasts.
	 * @param context The context to give to this static method
	 * @param text The text to show in the Toast
	 */
	private static void butter(Context context, String text) {
		Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
	}

	
	/**
	 * Sets mDoneGettingSdData to true to show that we're done with accepting
	 * SD data.
	 */
	private synchronized void setSdDataGettingDone() {
		mDoneGettingSdData = true;
	}

	
	/**
	 * Checks mDoneGettingSdData to see if we're still accepting SD data or not 
	 * @return <tt>true</tt>If we're done with accepting SD data, <tt>false</tt>
	 * otherwise
	 */
	private synchronized boolean isDoneGettingSdData() {
		return mDoneGettingSdData;
	}

	
	/**
	 * Sets up the BluetoothSerialService object, mBtss to begin BT-ing
	 */
	private void setupBt() {
		if (D) Log.d(TAG, "Bluetooth adapter successfully enabled");
		final BluetoothDevice mmDevice = getDevice();

		// If the device is paired, attempt connection 
		if (mmDevice != null) {
			mBtss = new BluetoothSerialService(this, mBtHandler);
			mBtss.start();
			butter(getApplicationContext(), "Connecting to the meter...");
			mBtss.connect(mmDevice);
		} else {
			if (D) Log.w(TAG, "Device couldn't be found! Starting AlertDialog");
			DialogInterface.OnClickListener listener =
					new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					switch (which) {
					case DialogInterface.BUTTON_POSITIVE:
                        if (D_TEST_GRAPH) {
                            dialog.dismiss();
                            butter(getApplicationContext(), "Device found!");
                            mUpdateTimerHandler.post(mUpdateTimerThread);
                            return;
                        }
						Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
						startActivityForResult(intent, OPEN_BT_SETTINGS);
						break;
					case DialogInterface.BUTTON_NEGATIVE:
						dialog.dismiss();
						finish();
					}
				}
			};
			new AlertDialog.Builder(MainActivity.this)
			.setTitle("Device not found!")
			.setIcon(android.R.drawable.ic_dialog_alert)
			.setMessage("Device not found. Open BT settings to pair it?")
			.setCancelable(false)
			.setPositiveButton("Yeah", listener)
			.setNegativeButton("Quit", listener)
			.show();
		}
	}

	
	/**
	 * This starts a timer that counts for 4 seconds while SD data is being
	 * read & stored. Once time is up, this'll stop the app from reading
	 * further SD data and switch to reading live data.
	 */
	private void startTimer() {
		new Thread(new Runnable() {
			public void run() {
				// Now, wait for 4 seconds for app to get all the SD data
				try {
					Thread.sleep(4000);
				} catch (InterruptedException e) {
//					if (D) Log.w("MainActivity.mGetSdDataThread", "Interrupted!");
				} finally {
					// Time be up, or not. Whatever happened, stop writing and
					// start getting RL data
					if (mWriter == null) {
						if (D) Log.w(TAG, "mWriter is null @ mGetSdDataThread!");
						butter(getApplicationContext(), 
								"SD data couldn't be read! Reading real-time data");
						setSdDataGettingDone();
						sendToDb('b');
						return;
					}
					mWriter.flush();
					mWriter.close();
//					if (D) {
//						Log.d("MainActivity.mGetSdDataThread",
//								"mWriter flushed and closed");
//						Log.d("MainActivity.mGetSdDataThread",
//								"mDoneGettingSdData -> true");
//					}
					setSdDataGettingDone();
					sendToDb('b');
				}
			}
		}).start();
	}

	/**
	 * This is the thread that sends the RL data to the database
	 */
	private final Runnable mUpdateTimerThread = new Runnable()
	{
		public void run()
		{
            if (D_TEST_GRAPH) {
                new TestGraphTask().execute();
            } else {
                Calendar mmCalendar = Calendar.getInstance();
                int hours = mmCalendar.get(Calendar.HOUR_OF_DAY);
                int minutes = mmCalendar.get(Calendar.MINUTE);
                int seconds = mmCalendar.get(Calendar.SECOND);
                // Extract the time from the calendar instance
                new SummaryAsyncTask().execute(mReceiveFromBluetooth,
                        String.valueOf(seconds),
                        String.valueOf(minutes),
                        String.valueOf(hours));
                // Write whatever you want to repeat here
                String serverURL = "http://test1-iggydomain.rhcloud.com/db.php";
                // Create Object and call AsyncTask execute Method
                new LongOperation().execute(serverURL);
            }
			if (D_TEST_GRAPH) mUpdateTimerHandler.postDelayed(this, 600);
            else mUpdateTimerHandler.postDelayed(this, 5000);
		}
	};

	/**
	 * Custom Handler class to handle Bluetoothing in this app.
	 * @author Sim
	 */
	private static class BtHandler extends Handler {

		private final MainActivity mRef;

		public BtHandler(MainActivity activity) {
			super();
			mRef = new WeakReference<MainActivity>(activity).get();
		}

		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MESSAGE_STATE_CHANGE:
				switch (msg.arg1) {
				case BluetoothSerialService.STATE_CONNECTED:
					if(D) Log.i(TAG, "Connected to device");
					MainActivity.butter(mRef.getApplicationContext(),
							mRef.getResources().getString(R.string.data_view_connected));
					// Connected; get SD data first. RL data will be gotten afterwards
					if (D_RL_DATA_ONLY) mRef.sendToDb('b');
					else mRef.sendToDb('a');
					break;

				case BluetoothSerialService.STATE_CONNECTING:
					if(D) Log.i(TAG, "Connecting to device");
					break;

				case BluetoothSerialService.STATE_LISTEN:
				case BluetoothSerialService.STATE_NONE:
					break;
				}
				break;

			case MESSAGE_WRITE:
				if (D) Log.i(TAG, "Sending signal: " + new String((byte[])msg.obj));
				break;

			case MESSAGE_READ:
				String message = (String)msg.obj;
				if (message.length() > 0) {
					// First check for mStartedGettingSdData for mGetSdDataThread
					// to start counting down
					// Even after SD data collection is done, checking for this
					// wouldn't affect anything
					if (!(mRef.mStartedGettingSdData)) {
						if (D) Log.d(TAG, "mStartedGettingSdData -> true");
						mRef.mStartedGettingSdData = true;
						if (D) Log.d(TAG, "mGetSdDataThread countdown started");
						mRef.startTimer();
					}
					if (!(mRef.isDoneGettingSdData())) {
						// We're still getting SD data, so write data to mSdDataFile
						mRef.mWriter.write(message);
						mRef.mWriter.flush();
						if (D) Log.d(TAG, "mWriter has written 'n' flushed");
					}
					mRef.mReceiveFromBluetooth = message;
					if (D) Log.i(TAG, "Received data: " + mRef.mReceiveFromBluetooth +
							"\t(" + msg.arg1 + " bytes)");
				} else {
					if (D) Log.i(TAG, "Data empty!!");
				}
				break;

			case MESSAGE_TOAST:
				Toast.makeText(mRef, msg.getData().getString(TOAST),
						Toast.LENGTH_SHORT).show();
				break;
			}
		}
	};


    /**
     * This class is meant to check if the graph works. It is activated if D_TEST_GRAPH and
     * D_SKIP_TO_GRAPH are set to true.
     * @author Sim
     */
    private class TestGraphTask extends AsyncTask<Void, Void, Void> {
        private ProgressDialog mDialog = new ProgressDialog(MainActivity.this);
        private String mContent = null;
        private Random mRandom = new Random();

        @Override
        protected Void doInBackground(Void... unused) {
            if (D) Log.d(TAG, "Currently in TestGraphTask doInBackground");
            mContent = "\"data\":\"" + 100 + "\"";
            if (D) Log.d(TAG, "Data to be appended " + mContent);
            return null;
        }

        @Override
        protected void onPostExecute(Void unused) {
            super.onPostExecute(unused);
            // Close progress dialog
            mDialog.dismiss();
            GraphFragment f = (GraphFragment)getFragmentManager()
                    .findFragmentById(R.id.container);
            if (f != null && f.isVisible()) {
                if (D) Log.d(TAG, "From activity, appending data");
                f.appendData(mContent);
            }
        }
    }

    /**
	 * This class sends the power data and the timestamp up to the database.
	 * @author Iggy
	 *
	 */
	private class SummaryAsyncTask extends AsyncTask<String, Integer, Double> {

		private void postData(String mesg, String sec, String min, String hour) {
			HttpClient httpclient = new DefaultHttpClient();
			HttpPost httppost = new HttpPost("http://test1-iggydomain.rhcloud.com/db.php");
			try {
				List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>();
				nameValuePairs.add(new BasicNameValuePair("message", mesg));
				nameValuePairs.add(new BasicNameValuePair("second", sec));
				nameValuePairs.add(new BasicNameValuePair("minute", min));
				nameValuePairs.add(new BasicNameValuePair("hour", hour));
				httppost.setEntity(new UrlEncodedFormEntity(nameValuePairs));
				httpclient.execute(httppost);
			}
			catch (ClientProtocolException e) {
				if (D) Log.e(TAG, "ClientProtocolException: " + e.getMessage());
				cancel(true);
			}
			catch (IOException e) {
				if (D) Log.e(TAG, "IOException: " + e.getMessage());
				cancel(true);
			}
		}

		@Override
		protected Double doInBackground(String... params) {
			postData(params[0], params[1], params[2], params[3]);
			return null;
		}
	}

	/**
	 * This class retrieves the data back from the database, which is made up of
	 * the average power collected plus its timestamp. The data is then used to
	 * plot the graph on the app.
	 * @author Iggy
	 *
	 */
	private class LongOperation extends AsyncTask<String, Void, Void> {
		private final HttpClient mClient = new DefaultHttpClient();
		private ProgressDialog mDialog = new ProgressDialog(MainActivity.this);
		private String mContent = null;
		private String mError = null;

		@Override
		protected Void doInBackground(String... urls) {
			try {
				if (D) Log.d(TAG, "Currently in LongOperation doInBackground");
				// Call long running operations here (perform background computation)
				// Server url call by GET method
				HttpGet httpget = new HttpGet(urls[0]);
				ResponseHandler<String> responseHandler = new BasicResponseHandler();
				mContent = mClient.execute(httpget, responseHandler); 
				if (D) Log.d(TAG, "Data retrieved from db: " + mContent);
			}
			catch (ClientProtocolException e) {
				mError = e.getMessage();
				if (D) Log.e(TAG, mError);
				cancel(true);
			}
			catch (IOException e) {
				mError = e.getMessage();
				if (D) Log.e(TAG, mError);
				cancel(true);
			}
			return null;
		}

		@Override
		protected void onPostExecute(Void unused) {
			super.onPostExecute(unused);
			// NOTE: You can call UI Element here.
			// Close progress dialog
			mDialog.dismiss();
			GraphFragment f = (GraphFragment)getFragmentManager()
					.findFragmentById(R.id.container);
			if (f != null && f.isVisible()) {
				if (D) Log.d(TAG, "From activity, appending data");
				f.appendData(mContent);
			}
		}
	}
}
