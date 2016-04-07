package sim.unistuff.d5_final;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.GridLabelRenderer;
import com.jjoe64.graphview.Viewport;
import com.jjoe64.graphview.helper.StaticLabelsFormatter;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import android.app.Fragment;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * This is the GraphFragment, where the main UI is at. The data received from
 * MainActivity is passed here to be processed, i.e. plotted and calculated for
 * total power used and cost. 
 * <p>The graph is created with a 3rd party library called the Android GraphView.
 * <a href="www.android-graphview.org">Here's the link to the place.</a>
 * <p>Apart from that measurements are taken i.e. the total power used and the
 * size of the hole it will cause to the user's wallet. And heart.
 */
public class GraphFragment extends Fragment {

	private static final boolean D = true;
	private static final boolean TEST_GRAPH = true;
	private static final String TAG = "GRAPH_FRAGMENT";
	private static final float CHARGE = 0.256f;
	private static final short DATA_POINTS_COUNT = 1800/5;

	private final Pattern mPattern = Pattern.compile("\"data\":\"\\d+\"");
	private final String[] X_LABEL = {"30", "25", "20", "15", "10", "5", "0"};

	private ArrayList<Integer> mNoDataList;
	private double count = 1d;
	private GraphView mGraph;
	private GridLabelRenderer mRenderer;
	private LineGraphSeries<DataPoint> mSeries;
	private Viewport mViewport;
	private TextView mCurrentRate, mPowerUsed, mTotalCost;

	public GraphFragment() {}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		View rootView = inflater.inflate(R.layout.fragment_graph, container, false);
		
		/// Initialising stuff
        mCurrentRate = (TextView) rootView.findViewById(R.id.current_rate_tv);
        mCurrentRate.setText("RM" + String.valueOf(CHARGE) + "/kWh");
		mPowerUsed = (TextView) rootView.findViewById(R.id.power_use_tv);
		mPowerUsed.setText("?.?kW");
		mTotalCost = (TextView) rootView.findViewById(R.id.total_cost_tv);
		mTotalCost.setText("RM?.??");
		mGraph = (GraphView) rootView.findViewById(R.id.graph);
		mRenderer = mGraph.getGridLabelRenderer();
		mViewport = mGraph.getViewport();
		
		/// Graph formatting code starts here
		mSeries = new LineGraphSeries<DataPoint>();
		mGraph.addSeries(mSeries);
		mSeries.appendData(new DataPoint(0,0), true, DATA_POINTS_COUNT);
		mViewport.setXAxisBoundsManual(true);
		mViewport.setMinX(count-DATA_POINTS_COUNT);
		mViewport.setMaxX(count);
		StaticLabelsFormatter formatter = new StaticLabelsFormatter(mGraph);
		formatter.setHorizontalLabels(X_LABEL);
		mRenderer.setLabelFormatter(formatter);
		mRenderer.setNumVerticalLabels(7);
		mRenderer.setHighlightZeroLines(false);
		mViewport.setYAxisBoundsManual(true);
		mViewport.setMinY(0);
		mViewport.setMaxY(250);
		/// Graph formatting code ends here
		
		return rootView;
	}
	
	private void printPowerAndCost() {
		if (D) Log.d(TAG, "Printing average power and cost to screen");
		double y = 0;
		Iterator<DataPoint> it = mSeries.getValues(0d, count);
		while (it.hasNext()) {
			y += it.next().getY();
		}
        y /= (1000*count);  // Get the average power
		mPowerUsed.setText(String.format("%.1fkW", y));
		mTotalCost.setText(String.format("RM%.2f", CHARGE*y*count/12));
	}
	
	/**
	 * Appends new power data to the GraphSeries
	 * @param power
	 */
	public void appendData(String power) {
		/// If power is valid data, append it to graph
		/// Else if power could not be recorded, append a '0'
		double p = 0.0;
		try {
			Matcher m = mPattern.matcher(power);
			String s = "";
			while (m.find()) {
				s = m.group();
			}
			if (D) Log.d(TAG, "Pattern match! " + s);
			s = s.substring(8, s.length()-1);
			if (D) Log.d(TAG, "Data found: " + s);
			p = Double.parseDouble(s);
			DataPoint dp = new DataPoint(count++, p);
			mSeries.appendData(dp, true, DATA_POINTS_COUNT);
			if (D) Log.d(TAG, "Appending data to graph");
			printPowerAndCost();
			if (D) Log.d(TAG, "Data received");
		} catch (NumberFormatException e) {
			/// Power could not be recorded 
			if (D) Log.w(TAG, "Data isn't valid!");
			mNoDataList.add((int)count+1);
			p = 0d;
		}
	}
}