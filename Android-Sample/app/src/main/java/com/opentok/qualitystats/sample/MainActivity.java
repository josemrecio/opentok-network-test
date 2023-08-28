package com.opentok.qualitystats.sample;

import android.Manifest;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.opentok.qualitystats.sample.models.VideoQualityTestConfig;
import com.opentok.qualitystats.sample.models.stats.QualityStats;

import java.util.ArrayList;
import java.util.List;

import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.AppSettingsDialog;
import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends Activity implements EasyPermissions.PermissionCallbacks {
    static final String LOGTAG = "quality-stats-demo";
    private static final int RC_VIDEO_APP_PERM = 124;
    private static final String SESSION_ID = "2_MX40NzczMDk4MX5-MTY5MzIxMDM4NjgwM355WjdoT05KR1REVEtub1FHZzkrYTZUc0J-fn4";
    private static final String TOKEN = "T1==cGFydG5lcl9pZD00NzczMDk4MSZzaWc9Zjc1YmI4NWRmZDQwOTI5MzBhOGEwODc2NWFlZTI5YWNmZGQwMDZlMDpzZXNzaW9uX2lkPTJfTVg0ME56Y3pNRGs0TVg1LU1UWTVNekl4TURNNE5qZ3dNMzU1V2pkb1QwNUtSMVJFVkV0dWIxRkhaemtyWVRaVWMwSi1mbjQmY3JlYXRlX3RpbWU9MTY5MzIxMDM4NyZub25jZT0wLjE5MDYwODMyNTQyODEwODU3JnJvbGU9bW9kZXJhdG9yJmV4cGlyZV90aW1lPTE2OTU4MDIzODcmaW5pdGlhbF9sYXlvdXRfY2xhc3NfbGlzdD0=";
    private static final String APIKEY = "47730981";
    private final List<Double> testResults = new ArrayList<>();
    private LineChart chart;
    private View statsTextViewSub;
    private View statsTextView;

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override
    public void onPermissionsGranted(int requestCode, List<String> perms) {
        Log.d(LOGTAG, "onPermissionsGranted:" + requestCode + ":" + perms.size());
    }

    @Override
    public void onPermissionsDenied(int requestCode, List<String> perms) {
        Log.d(LOGTAG, "onPermissionsDenied:" + requestCode + ":" + perms.size());
        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            new AppSettingsDialog.Builder(this)
                    .setTitle(getString(R.string.title_settings_dialog))
                    .setRationale(getString(R.string.rationale_ask_again))
                    .setPositiveButton(getString(R.string.setting))
                    .setNegativeButton(getString(R.string.cancel))
                    .setRequestCode(RC_VIDEO_APP_PERM)
                    .build()
                    .show();
        }
    }

    @AfterPermissionGranted(RC_VIDEO_APP_PERM)
    private void requestPermissions() {
        String[] perms = {android.Manifest.permission.INTERNET, android.Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        if (EasyPermissions.hasPermissions(this, perms)) {
            createAndLaunchTest();
        } else {
            EasyPermissions.requestPermissions(this, getString(R.string.rationale_video_app), RC_VIDEO_APP_PERM, perms);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        chart = findViewById(R.id.chart);
        statsTextView = findViewById(R.id.statsTextView);
        statsTextViewSub = findViewById(R.id.statsSubscriber);

        requestPermissions();
    }

    private void createAndLaunchTest() {
        // Create a VideoQualityTestConfig instance
        VideoQualityTestConfig config = new VideoQualityTestConfig.Builder()
                .sessionId(SESSION_ID)
                .apiKey(APIKEY)
                .token(TOKEN)
                .build();

        // Create a VideoQualityTest instance
        VideoQualityTest videoQualityTest = new VideoQualityTest(this, config, new VideoQualityTest.VideoQualityTestListener() {
            @Override
            public void onTestResult(String recommendedSetting) {
                Log.d(LOGTAG, "Recommended resolution: " + recommendedSetting);
            }

            @Override
            public void onTestUpdate(QualityStats stats) {
                Log.d(LOGTAG, "---------------------------------------------------------------");
                Log.d(LOGTAG, "Sent Video Bitrate: " + stats.getSentVideoBitrateKbps() + " Kbps");
                Log.d(LOGTAG, "Sent Audio Bitrate: " + stats.getSentAudioBitrateKbps() + " Kbps");
                Log.d(LOGTAG, "Received Audio Bitrate: " + stats.getReceivedAudioBitrateKbps() + " Kbps");
                Log.d(LOGTAG, "Received Video Bitrate: " + stats.getReceivedVideoBitrateKbps() + " Kbps");
                Log.d(LOGTAG, "Current Round Trip Time: " + stats.getCurrentRoundTripTimeMs() + " ms");
                Log.d(LOGTAG, "Available Outgoing Bitrate: " + stats.getAvailableOutgoingBitrate() + " bps");
                Log.d(LOGTAG, "Audio Packet Lost Ratio  " + stats.getAudioPacketLostRatio() * 100 + "%");
                Log.d(LOGTAG, "Video Packet Lost Ratio  " + stats.getVideoPacketLostRatio() * 100 + "%");
                Log.d(LOGTAG, "Jitter: " + stats.getJitter());
                Log.d(LOGTAG, "Quality Limitation Reason: " + stats.getQualityLimitationReason());
                Log.d(LOGTAG, "Sent video resolution: " + stats.getSentVideoResolution());
                Log.d(LOGTAG, "Received video resolution: " + stats.getReceivedVideoResolution());
                Log.d(LOGTAG, "---------------------------------------------------------------");
            }

            @Override
            public void onError(String error) {
                Log.d(LOGTAG, "Error " + error);
            }
        });

        // Start the quality test
        videoQualityTest.startTest();
    }

    private void updateChart() {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < testResults.size(); i++) {
            // i is used as the x-value to represent time in seconds
            entries.add(new Entry(i, testResults.get(i).floatValue()));
        }

        LineDataSet dataSet = new LineDataSet(entries, "Available Outgoing Bitrate");
        dataSet.setColor(Color.BLUE); // Set the line color
        dataSet.setCircleColor(Color.BLUE); // Set the circle color
        dataSet.setLineWidth(2f); // Set the line width
        dataSet.setCircleRadius(3f); // Set the circle radius
        dataSet.setDrawCircleHole(true); // Disable the circle hole
        dataSet.setDrawValues(false); // Hide the values displayed on the chart
        dataSet.setDrawFilled(true); // Enable filling below the line
        dataSet.setFormLineWidth(1f); // Set the line width of the legend form
        dataSet.setFormLineDashEffect(new DashPathEffect(new float[]{10f, 5f}, 0f)); // Set the line dash effect of the legend form
        dataSet.setFormSize(15.f); // Set the size of the legend form

        LineData lineData = new LineData(dataSet);
        chart.setData(lineData);

        // Set the minimum values for the axes to 0
        chart.getXAxis().setAxisMinimum(0f);
        chart.getAxisLeft().setAxisMinimum(0f);
        chart.getAxisLeft().setAxisMaximum(5550000f); // Set the maximum value to 5550000

        // Disable the right y-axis
        chart.getAxisRight().setEnabled(false);

        // Set the x-axis to display at the bottom
        chart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);

        // Set the description text
        chart.getDescription().setText("Time (s)");

        // Customize the chart appearance
        chart.setDrawGridBackground(false); // Disable the grid background
        chart.setDrawBorders(false); // Disable the borders
        chart.getLegend().setEnabled(false); // Disable the legend

        // Customize the x-axis appearance
        XAxis xAxis = chart.getXAxis();
        xAxis.setDrawGridLines(false); // Disable the x-axis grid lines
        xAxis.setDrawAxisLine(false); // Disable the x-axis line

        // Customize the y-axis appearance
        YAxis yAxis = chart.getAxisLeft();
        yAxis.setDrawGridLines(false); // Disable the y-axis grid lines
        yAxis.setDrawAxisLine(false); // Disable the y-axis line

        chart.invalidate(); // refresh the chart
    }

}
