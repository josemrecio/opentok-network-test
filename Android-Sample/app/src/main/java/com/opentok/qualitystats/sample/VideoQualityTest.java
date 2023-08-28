package com.opentok.qualitystats.sample;


import android.content.Context;
import android.os.Handler;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.opentok.android.OpentokError;
import com.opentok.android.Publisher;
import com.opentok.android.PublisherKit;
import com.opentok.android.Session;
import com.opentok.android.Session.SessionListener;
import com.opentok.android.Stream;
import com.opentok.android.Subscriber;
import com.opentok.android.SubscriberKit;
import com.opentok.qualitystats.sample.models.QualityTestResult;
import com.opentok.qualitystats.sample.models.QualityThreshold;
import com.opentok.qualitystats.sample.models.VideoQualityTestConfig;
import com.opentok.qualitystats.sample.models.stats.MediaStatsEntry;
import com.opentok.qualitystats.sample.models.stats.VideoQualityStats;
import com.opentok.qualitystats.sample.models.stats.QualityStats;
import com.opentok.qualitystats.sample.models.stats.SubscriberAudioStats;
import com.opentok.qualitystats.sample.models.stats.SubscriberVideoStats;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public class VideoQualityTest extends AppCompatActivity
        implements SessionListener, PublisherKit.PublisherListener, SubscriberKit.SubscriberListener {

    private static final String LOGTAG = "quality-stats";
    private static final int TIME_WINDOW = 1;
    private final Handler mHandler = new Handler();
    private final Queue<SubscriberKit.SubscriberVideoStats> videoStatsQueue = new LinkedList<>();
    private final Queue<SubscriberKit.SubscriberAudioStats> audioStatsQueue = new LinkedList<>();
    private final Context context;
    private final VideoQualityTestConfig config;
    private final VideoQualityTestListener listener;
    private final Handler rtcStatsHandler = new Handler();
    private final Map<Long, Long> ssrcToPrevBytesSent = new HashMap<>();
    private final List<VideoQualityStats> publisherVideoQualityStatsList = new ArrayList<>();
    private final List<VideoQualityStats> subscriberVideoQualityStatsList = new ArrayList<>();
    private final List<SubscriberVideoStats> subscriberVideoStatsList = new ArrayList<>();
    private final List<SubscriberAudioStats> subscriberAudioStatsList = new ArrayList<>();
    private Session mSession;

    private Publisher mPublisher;
    private Subscriber mSubscriber;
    private Runnable rtcStatsRunnable;
    private Runnable qualityStatsRunnable;

    private long prevVideoTimestamp = 0;


    private long mStartTestTime = 0;
    private Double estimatedAvailableOutgoingBitrate = 0.0;

    public VideoQualityTest(Context context, VideoQualityTestConfig config, VideoQualityTestListener listener) {
        this.context = context;
        this.config = config;
        this.listener = listener;
    }

    public void startTest() {
        // Initialize and connect to the OpenTok session

        mSession = new Session.Builder(context, config.getApiKey(), config.getSessionId()).build();
        mSession.setSessionListener(this);
        // Connect to the session
        mSession.connect(config.getToken());
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();

        if (mSession != null) {
            mSession.disconnect();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    public void sessionConnect() {
        Log.i(LOGTAG, "Connecting session");
        if (mSession == null) {
            mSession = new Session.Builder(this, config.getApiKey(), config.getSessionId()).build();
            mSession.setSessionListener(this);
            mSession.connect(config.getToken());
        }
    }

    @Override
    public void onConnected(Session session) {
        Log.i(LOGTAG, "Session is connected");

        mPublisher = new Publisher.Builder(context)
                .resolution(Publisher.CameraCaptureResolution.HIGH_1080P)
                .build();
        mPublisher.setPublisherListener(this);
        mPublisher.setAudioFallbackEnabled(false);
        mSession.publish(mPublisher);
    }

    @Override
    public void onDisconnected(Session session) {
        Log.i(LOGTAG, "Session is disconnected");
        mPublisher = null;
        mSubscriber = null;
        mSession = null;
        stopStatsCollection();
    }

    @Override
    public void onError(Session session, OpentokError opentokError) {
        Log.i(LOGTAG, "Session error: " + opentokError.getMessage());

    }

    private void startStatsCollection() {
        rtcStatsRunnable = () -> {
            if (mPublisher != null) {
                mPublisher.getRtcStatsReport();
                rtcStatsHandler.postDelayed(rtcStatsRunnable, 500);
            }
        };
        rtcStatsHandler.post(rtcStatsRunnable);

    }

    private void stopStatsCollection() {
        rtcStatsHandler.removeCallbacks(rtcStatsRunnable);
        mHandler.removeCallbacks(qualityStatsRunnable);
    }

    private QualityTestResult getRecommendedSetting() {
        final int NUMBER_OF_OUTGOING_AVAILABLE_BITRATE_SAMPLES = 5;
        LinkedList<Long> outgoingBitrateValues = new LinkedList<>();
        for (VideoQualityStats videoQualityStats : publisherVideoQualityStatsList) {
            if (outgoingBitrateValues.size() == NUMBER_OF_OUTGOING_AVAILABLE_BITRATE_SAMPLES) {
                outgoingBitrateValues.removeFirst();
            }
            long availableOutgoingBitrate = videoQualityStats.getAvailableOutgoingBitrate();
            outgoingBitrateValues.addLast(availableOutgoingBitrate);
        }
        // TODO: change to exponential average so the latest measurements have more weight?
        estimatedAvailableOutgoingBitrate = 0.0;
        int i = 0;
        for (long o : outgoingBitrateValues) {
            estimatedAvailableOutgoingBitrate = (estimatedAvailableOutgoingBitrate + (o - estimatedAvailableOutgoingBitrate) / (i + 1));
            i++;
        }
        Log.d(LOGTAG, "Estimated available outgoing bitrate: " + estimatedAvailableOutgoingBitrate);
        for (QualityThreshold threshold : qualityThresholds) {
            if (estimatedAvailableOutgoingBitrate >= threshold.getTargetBitrate()) {
                Log.d(LOGTAG, "Recommended Bitrate: " + threshold.getRecommendedSetting());
                return new QualityTestResult(threshold.getRecommendedSetting());
            }
        }
        Log.d(LOGTAG, "Bitrate is too low for video");
        return new QualityTestResult("Bitrate is too low for video");
    }

    @Override
    public void onStreamDropped(Session session, Stream stream) {
        Log.i(LOGTAG, "Session onStreamDropped");
    }

    @Override
    public void onStreamReceived(Session session, Stream stream) {
        Log.i(LOGTAG, "Session onStreamReceived");
    }

    @Override
    public void onStreamCreated(PublisherKit publisherKit, Stream stream) {
        Log.i(LOGTAG, "Publisher onStreamCreated");
        if (mSubscriber == null) {
            subscribeToStream(stream);
        }
    }

    @Override
    public void onStreamDestroyed(PublisherKit publisherKit, Stream stream) {
        Log.i(LOGTAG, "Publisher onStreamDestroyed");
        if (mSubscriber == null) {
            unsubscribeFromStream(stream);
        }
    }

    @Override
    public void onError(PublisherKit publisherKit, OpentokError opentokError) {
        Log.i(LOGTAG, "Publisher error: " + opentokError.getMessage());
    }

    @Override
    public void onConnected(SubscriberKit subscriberKit) {
        Log.i(LOGTAG, "Subscriber onConnected");
        // Mute Subscriber Audio
        subscriberKit.setAudioVolume(0);
        mHandler.postDelayed(statsRunnable, config.getTestDurationSec() * 1000L);
        // Initialize and post the Runnable to get RTC stats every second
        rtcStatsRunnable = new Runnable() {
            @Override
            public void run() {
                if (mPublisher != null) {
                    mPublisher.getRtcStatsReport();
                    mSubscriber.getRtcStatsReport();
                    rtcStatsHandler.postDelayed(this, 500);
                }
            }
        };
        rtcStatsHandler.post(rtcStatsRunnable);

        qualityStatsRunnable = () -> {
            if (mSession != null) {
                QualityStats qualityStats = calculateQualityStats();
                if (qualityStats != null) {
                    listener.onTestUpdate(qualityStats);
                }
                mHandler.postDelayed(qualityStatsRunnable, 1000); // Call every one second
            }
        };
        mHandler.post(qualityStatsRunnable);

    }

    @Override
    public void onDisconnected(SubscriberKit subscriberKit) {
        Log.i(LOGTAG, "Subscriber onDisconnected");
    }

    @Override
    public void onError(SubscriberKit subscriberKit, OpentokError opentokError) {
        Log.i(LOGTAG, "Subscriber error: " + opentokError.getMessage());
    }

    private void subscribeToStream(Stream stream) {

        mSubscriber = new Subscriber.Builder(context, stream).build();
        mSubscriber.setSubscriberListener(this);
        mSession.subscribe(mSubscriber);
        mSubscriber.setVideoStatsListener((subscriber, stats) -> {
            if (mStartTestTime == 0) {
                mStartTestTime = System.currentTimeMillis() / 1000;
            }
            onSubscriberVideoStats(stats);
            mPublisher.setRtcStatsReportListener(publisherRtcStatsReportListener);
            mSubscriber.setRtcStatsReportListener(subscriberRtcStatsReportListener);
            //check quality of the video call after TIME_VIDEO_TEST seconds
            if (((System.currentTimeMillis() / 1000 - mStartTestTime) > config.getTestDurationSec())) {
                //getRecommendedSetting();
            }
        });
        mSubscriber.setAudioStatsListener((subscriber, stats) -> onSubscriberAudioStats(stats));

    }

    private void unsubscribeFromStream(Stream stream) {
        if (mSubscriber.getStream().equals(stream)) {
            mSubscriber = null;
        }
    }

    private void onSubscriberVideoStats(SubscriberKit.SubscriberVideoStats videoStats) {
        double videoTimestamp = videoStats.timeStamp;
        // Initialize values for video
        if (videoStatsQueue.isEmpty()) {
            videoStatsQueue.add(videoStats);
            return;
        }
        SubscriberKit.SubscriberVideoStats previousVideoStats = videoStatsQueue.peek();

        if ((videoTimestamp - previousVideoStats.timeStamp) < TIME_WINDOW * 1000) {
            return;
        }

        // Video Stats
        long videoPacketsLostInterval = videoStats.videoPacketsLost - previousVideoStats.videoPacketsLost;
        long videoPacketsReceivedInterval = videoStats.videoPacketsReceived - previousVideoStats.videoPacketsReceived;
        long videoTotalPacketsInterval = videoPacketsLostInterval + videoPacketsReceivedInterval;
        double videoPLRatio = 0.0;
        if (videoTotalPacketsInterval > 0) {
            videoPLRatio = (double) videoPacketsLostInterval / (double) videoTotalPacketsInterval;
        }

        long elapsedTimeMs = (long) (videoTimestamp - previousVideoStats.timeStamp);
        long bytesSentDiff = videoStats.videoBytesReceived - previousVideoStats.videoBytesReceived;
        long videoBitrateKbps = (long) ((bytesSentDiff * 8) / (elapsedTimeMs / 1000.0)) / 1000;

        videoStatsQueue.add(videoStats);

        subscriberVideoStatsList.add(new SubscriberVideoStats.Builder()
                .videoBytesKbsReceived(videoBitrateKbps)
                .videoBytesReceived(videoStats.videoBytesReceived)
                .timestamp(videoStats.timeStamp)
                .videoPacketLostRatio(videoPLRatio)
                .build());

    }

    private void onSubscriberAudioStats(SubscriberKit.SubscriberAudioStats audioStats) {
        double audioTimestamp = audioStats.timeStamp;

        // Initialize values for audio
        if (audioStatsQueue.isEmpty()) {
            audioStatsQueue.add(audioStats);
            return;
        }

        SubscriberKit.SubscriberAudioStats previousAudioStats = audioStatsQueue.peek();

        // Check if the time difference is within the time window
        if ((audioTimestamp - previousAudioStats.timeStamp) < TIME_WINDOW * 1000) {
            return;
        }

        // Audio Stats
        long audioPacketsLostInterval = audioStats.audioPacketsLost - previousAudioStats.audioPacketsLost;
        long audioPacketsReceivedInterval = audioStats.audioPacketsReceived - previousAudioStats.audioPacketsReceived;
        long audioTotalPacketsInterval = audioPacketsLostInterval + audioPacketsReceivedInterval;

        double audioPLRatio = 0.0;
        if (audioTotalPacketsInterval > 0) {
            audioPLRatio = (double) audioPacketsLostInterval / (double) audioTotalPacketsInterval;
        }

        // Calculate audio bandwidth
        long elapsedTimeMs = (long) (audioTimestamp - previousAudioStats.timeStamp);
        long audioBw = (long) ((8L * (audioStats.audioBytesReceived - previousAudioStats.audioBytesReceived)) / (elapsedTimeMs / 1000.0));

        audioStatsQueue.add(audioStats);

        subscriberAudioStatsList.add(SubscriberAudioStats.builder()
                .audioBitrateKbps(audioBw / 1000) // Convert to Kbps
                .audioBytesReceived(audioStats.audioBytesReceived)
                .timestamp(audioStats.timeStamp)
                .audioPacketLostRatio(audioPLRatio)
                .build());
    }

    private long calculateVideoBitrateKbps(long ssrc, long timestamp, long currentBytesSent) {
        long videoBitrateKbps = 0;
        if (prevVideoTimestamp > 0 && ssrcToPrevBytesSent.containsKey(ssrc)) {
            long elapsedTimeMs = timestamp - prevVideoTimestamp;
            long prevBytesSent = ssrcToPrevBytesSent.get(ssrc);
            long bytesSentDiff = currentBytesSent - prevBytesSent;
            videoBitrateKbps = (long) ((bytesSentDiff * 8) / (elapsedTimeMs / 1000.0)); // Calculate Kbps
        }
        ssrcToPrevBytesSent.put(ssrc, currentBytesSent);
        return videoBitrateKbps;
    }

    private final Runnable statsRunnable = new Runnable() {
        @Override
        public void run() {
            if (mSession != null) {
                // rtcStatsHandler.removeCallbacks(rtcStatsRunnable);
                // mSession.disconnect();
                // Stop getting RTC stats
            }
        }
    };
    private final PublisherKit.PublisherRtcStatsReportListener publisherRtcStatsReportListener = (publisherKit, publisherRtcStats) -> {
        for (PublisherKit.PublisherRtcStats s : publisherRtcStats) {
            try {
                JSONArray rtcStatsJsonArray = new JSONArray(s.jsonArrayOfReports);
                List<MediaStatsEntry> videoStatsList = new ArrayList<>();
                MediaStatsEntry audioStats = null;

                double jitter = 0.0;
                long availableOutgoingBitrate = 0;
                long timestamp = 0;
                double currentRoundTripTimeMs = 0;

                for (int i = 0; i < rtcStatsJsonArray.length(); i++) {
                    JSONObject rtcStatObject = rtcStatsJsonArray.getJSONObject(i);
                    String statType = rtcStatObject.getString("type");
                    String kind = rtcStatObject.optString("kind", "none");
                    // Handle video and audio stats
                    if (statType.equals("outbound-rtp") && (kind.equals("video") || kind.equals("audio"))) {
                        long ssrc = rtcStatObject.getLong("ssrc");
                        String qualityLimitationReason = rtcStatObject.optString("qualityLimitationReason", "none");
                        String resolution = rtcStatObject.optInt("frameWidth", 0) + "x" + rtcStatObject.optInt("frameHeight", 0);
                        int framerate = rtcStatObject.optInt("framesPerSecond", 0);
                        int pliCount = rtcStatObject.optInt("pliCount", 0);
                        int nackCount = rtcStatObject.optInt("nackCont", 0);
                        long bytesSent = rtcStatObject.optInt("bytesSent", 0);
                        long bitrateKbps = calculateVideoBitrateKbps(ssrc,
                                rtcStatObject.optLong("timestamp", 0),
                                bytesSent);


                        MediaStatsEntry mediaStatsEntry = MediaStatsEntry.builder()
                                .ssrc(ssrc)
                                .qualityLimitationReason(qualityLimitationReason)
                                .resolution(resolution)
                                .framerate(framerate)
                                .pliCount(pliCount)
                                .nackCount(nackCount)
                                .bytesSent(bytesSent)
                                .bitrateKbps(bitrateKbps)
                                .build();

                        if ("video".equals(kind)) {
                            videoStatsList.add(mediaStatsEntry);
                        } else if ("audio".equals(kind)) {
                            audioStats = mediaStatsEntry;
                        }
                    }
                    // Handle candidate-pair stats
                    else if (statType.equals("candidate-pair")) {
                        boolean isNominated = rtcStatObject.optBoolean("nominated", false);
                        if (isNominated) {
                            availableOutgoingBitrate = rtcStatObject.optLong("availableOutgoingBitrate", 0);
                            currentRoundTripTimeMs = rtcStatObject.optDouble("currentRoundTripTime", 0) * 1000;
                            timestamp = rtcStatObject.optLong("timestamp", 0);
                        }
                    } else if (statType.equals("remote-inbound-rtp")) {
                        if ("video".equals(kind)) {
                            jitter = rtcStatObject.optDouble("jitter", -1);
                        }
                    }
                }

                prevVideoTimestamp = timestamp;

                VideoQualityStats videoQualityStats = VideoQualityStats.builder()
                        .videoStats(videoStatsList)
                        .audioStats(audioStats)
                        .jitter(jitter)
                        .currentRoundTripTimeMs(currentRoundTripTimeMs)
                        .availableOutgoingBitrate(availableOutgoingBitrate)
                        .timestamp(timestamp)
                        .build();

                publisherVideoQualityStatsList.add(videoQualityStats);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    };

    private final SubscriberKit.SubscriberRtcStatsReportListener subscriberRtcStatsReportListener = (subscriberKit, subscriberRtcStats) -> {
        try {
            JSONArray rtcStatsJsonArray = new JSONArray(subscriberRtcStats);
            List<MediaStatsEntry> videoStatsList = new ArrayList<>();
            MediaStatsEntry audioStats = null;

            VideoQualityStats videoQualityStats = processRtcStats(rtcStatsJsonArray, videoStatsList, audioStats);
            subscriberVideoQualityStatsList.add(videoQualityStats);

        } catch (JSONException e) {
            e.printStackTrace();
        }
    };

    private VideoQualityStats processRtcStats(JSONArray rtcStatsJsonArray, List<MediaStatsEntry> videoStatsList, MediaStatsEntry audioStats) throws JSONException {
        double jitter = 0.0;
        long availableOutgoingBitrate = 0;
        long timestamp = 0;
        double currentRoundTripTimeMs = 0;

        for (int i = 0; i < rtcStatsJsonArray.length(); i++) {
            JSONObject rtcStatObject = rtcStatsJsonArray.getJSONObject(i);
            String statType = rtcStatObject.getString("type");

            if (statType.equals("inbound-rtp")) {
                MediaStatsEntry mediaStatsEntry = processInboundRtp(rtcStatObject);
                String kind = rtcStatObject.optString("kind", "none");

                if ("video".equals(kind)) {
                    videoStatsList.add(mediaStatsEntry);
                } else if ("audio".equals(kind)) {
                    audioStats = mediaStatsEntry;
                }

                jitter = rtcStatObject.optDouble("jitter", -1);
            } else if (statType.equals("candidate-pair")) {
                boolean isNominated = rtcStatObject.optBoolean("nominated", false);

                if (isNominated) {
                    availableOutgoingBitrate = rtcStatObject.optLong("availableOutgoingBitrate", 0);
                    currentRoundTripTimeMs = rtcStatObject.optDouble("currentRoundTripTime", 0) * 1000;
                    timestamp = rtcStatObject.optLong("timestamp", 0);
                }
            }
        }

        prevVideoTimestamp = timestamp;

        return VideoQualityStats.builder()
                .videoStats(videoStatsList)
                .audioStats(audioStats)
                .jitter(jitter)
                .currentRoundTripTimeMs(currentRoundTripTimeMs)
                .availableOutgoingBitrate(availableOutgoingBitrate)
                .timestamp(timestamp)
                .build();
    }

    private MediaStatsEntry processInboundRtp(JSONObject rtcStatObject) throws JSONException {
        long ssrc = rtcStatObject.getLong("ssrc");
        String qualityLimitationReason = rtcStatObject.optString("qualityLimitationReason", "none");
        String resolution = rtcStatObject.optInt("frameWidth", 0) + "x" + rtcStatObject.optInt("frameHeight", 0);
        int framerate = rtcStatObject.optInt("framesPerSecond", 0);
        int pliCount = rtcStatObject.optInt("pliCount", 0);
        int nackCount = rtcStatObject.optInt("nackCount", 0);
        long bytesSent = rtcStatObject.optInt("bytesSent", 0);
        long bitrateKbps = calculateVideoBitrateKbps(ssrc, rtcStatObject.optLong("timestamp", 0), bytesSent);

        return MediaStatsEntry.builder()
                .ssrc(ssrc)
                .qualityLimitationReason(qualityLimitationReason)
                .resolution(resolution)
                .framerate(framerate)
                .pliCount(pliCount)
                .nackCount(nackCount)
                .bytesSent(bytesSent)
                .bitrateKbps(bitrateKbps)
                .build();
    }

    private QualityStats calculateQualityStats() {
        SubscriberVideoStats latestSubscriberVideoStats = null;
        SubscriberAudioStats latestSubscriberAudioStats = null;
        VideoQualityStats latestPublisherVideoQualityStats = null;
        VideoQualityStats latestSubscriberVideoQualityStats = null;

        if (!subscriberVideoStatsList.isEmpty()) {
            latestSubscriberVideoStats = subscriberVideoStatsList.get(subscriberVideoStatsList.size() - 1);
        }

        if (!subscriberAudioStatsList.isEmpty()) {
            latestSubscriberAudioStats = subscriberAudioStatsList.get(subscriberAudioStatsList.size() - 1);
        }

        if (!publisherVideoQualityStatsList.isEmpty()) {
            latestPublisherVideoQualityStats = publisherVideoQualityStatsList.get(publisherVideoQualityStatsList.size() - 1);
        }

        if (!subscriberVideoQualityStatsList.isEmpty()) {
            latestSubscriberVideoQualityStats = subscriberVideoQualityStatsList.get(subscriberVideoQualityStatsList.size() - 1);
        }

        if (latestSubscriberVideoStats != null && latestSubscriberAudioStats != null && latestPublisherVideoQualityStats != null) {
            return new QualityStats.Builder()
                    .sentVideoBitrateKbps(latestPublisherVideoQualityStats.getTotalVideoKbsSent())
                    .sentAudioBitrateKbps(latestPublisherVideoQualityStats.getAudioStats().getBitrateKbps())
                    .receivedAudioBitrateKbps(latestSubscriberAudioStats.getAudioBitrateKbps())
                    .receivedVideoBitrateKbps(latestSubscriberVideoStats.getVideoBytesKbsReceived())
                    .currentRoundTripTimeMs(latestPublisherVideoQualityStats.getCurrentRoundTripTimeMs())
                    .availableOutgoingBitrate(latestPublisherVideoQualityStats.getAvailableOutgoingBitrate())
                    .audioPacketLostRatio(latestSubscriberAudioStats.getAudioPacketLostRatio())
                    .videoPacketLostRatio(latestSubscriberVideoStats.getVideoPacketLostRatio())
                    .timestamp(latestPublisherVideoQualityStats.getTimestamp())
                    .jitter(latestSubscriberVideoQualityStats.getJitter())
                    .qualityLimitationReason(latestPublisherVideoQualityStats.getQualityLimitationReason())
                    .sentVideoResolution(latestPublisherVideoQualityStats.getResolutionBySrc())
                    .receivedVideoResolution(latestSubscriberVideoQualityStats.getResolutionBySrc())
                    .build();
        }

        return null; // Return null if necessary data is not available
    }


    public interface VideoQualityTestListener {
        void onTestResult(String recommendedSetting);

        void onTestUpdate(QualityStats stats);

        void onError(String error);
    }

    QualityThreshold[] qualityThresholds = new QualityThreshold[]{
            new QualityThreshold(4000000, 5550000, "1920x1080 @ 30FPS"),
            new QualityThreshold(2500000, 3150000, "1280x720 @ 30FPS"),
            new QualityThreshold(1200000, 1550000, "960x540 @ 30FPS"),
            new QualityThreshold(500000, 650000, "640x360 @ 30FPS"),
            new QualityThreshold(300000, 350000, "480x270 @ 30FPS"),
            new QualityThreshold(150000, 150000, "320x180 @ 30FPS")
    };

}
